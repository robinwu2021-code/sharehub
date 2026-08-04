package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.BillingDefaultRule;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.BizRules;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.ReservationRule;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.WithdrawRule;
import ai.neargo.sharehub.platform.sys.entity.SysBizRule;
import ai.neargo.sharehub.platform.sys.mapper.SysBizRuleMapper;
import ai.neargo.sharehub.platform.sys.service.BizRuleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业务规则实现 —— 三分区单例，**不走通用 CRUD**（没有列表、没有业务键，读写形态与字典表不同）。
 *
 * <p>库内是 {@code sys_biz_rule} 的三行（{@code WITHDRAW}/{@code RESERVATION}/{@code BILLING}），
 * UK {@code (tenant_id, category)}；对外是一个 {@code BizRules} 对象。
 *
 * <p><b>保存是 Partial 语义</b>：页面三个分区各有独立保存按钮，一次 POST 通常只带一个非空分区。
 * 本实现**只 upsert 传入的分区**——若改成整体覆盖，用户存「预约」就会把刚改的「提现」冲回旧值，
 * 而且没有任何提示。
 *
 * <p><b>{@code WITHDRAW} 是提现手续费口径的唯一来源</b>：{@link #withdrawRule()} 是财务侧唯一入口，
 * 不要在 fin 域再存一份 feeRate/feeCap。
 */
@Service
public class BizRuleServiceImpl implements BizRuleService {

    private static final String WITHDRAW = "WITHDRAW";
    private static final String RESERVATION = "RESERVATION";
    private static final String BILLING = "BILLING";
    private static final String DEFAULT_CURRENCY = "AED";

    /** 分区缺失时的兜底值：页面永远拿到完整对象，不必在前端写一遍默认值。 */
    private static final WithdrawRule DEFAULT_WITHDRAW = new WithdrawRule(
            new BigDecimal("50.00"), new BigDecimal("0.006"), new BigDecimal("20.00"),
            7, new BigDecimal("50000.00"), true);
    private static final ReservationRule DEFAULT_RESERVATION = new ReservationRule(
            30, 24, new BigDecimal("0.10"), 1);
    private static final BillingDefaultRule DEFAULT_BILLING = new BillingDefaultRule(
            5, 30, new BigDecimal("30.00"), new BigDecimal("99.00"), 72);

    private final SysBizRuleMapper mapper;

    public BizRuleServiceImpl(SysBizRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BizRules get() {
        SysBizRule w = row(WITHDRAW);
        SysBizRule r = row(RESERVATION);
        SysBizRule b = row(BILLING);

        return new BizRules(
                parse(w, WithdrawRule.class, DEFAULT_WITHDRAW),
                parse(r, ReservationRule.class, DEFAULT_RESERVATION),
                parse(b, BillingDefaultRule.class, DEFAULT_BILLING),
                currencyOf(w, r, b),
                SysCtx.fmt(latest(w, r, b)));
    }

    @Override
    public WithdrawRule withdrawRule() {
        return parse(row(WITHDRAW), WithdrawRule.class, DEFAULT_WITHDRAW);
    }

    @Override
    public BizRules save(BizRules partial) {
        if (partial == null) throw new IllegalArgumentException("请求体为空");
        if (partial.withdraw() == null && partial.reservation() == null && partial.billing() == null) {
            throw new IllegalArgumentException("至少需要提交一个分区（withdraw/reservation/billing）");
        }

        // 只动传入的分区 —— 未传的两个分区连 UPDATE 都不发
        if (partial.withdraw() != null) upsert(WITHDRAW, partial.withdraw(), partial.currency());
        if (partial.reservation() != null) upsert(RESERVATION, partial.reservation(), partial.currency());
        if (partial.billing() != null) upsert(BILLING, partial.billing(), partial.currency());

        return get();
    }

    // ——————————————————————— 内部 ———————————————————————

    private SysBizRule row(String category) {
        return mapper.selectOne(new QueryWrapper<SysBizRule>()
                .eq("category", category)
                .eq("tenant_id", SysCtx.tenantId())
                .last("limit 1"));
    }

    private void upsert(String category, Object rule, String currency) {
        SysBizRule cur = row(category);
        String body = write(rule);

        if (cur == null) {
            SysBizRule e = new SysBizRule();
            e.setTenantId(SysCtx.tenantId());
            e.setCategory(category);
            e.setRule(body);
            e.setCurrency(currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency);
            e.setUpdatedBy(SysCtx.operator());
            mapper.insert(e);
        } else {
            cur.setRule(body);
            if (currency != null && !currency.isBlank()) cur.setCurrency(currency);
            cur.setUpdatedBy(SysCtx.operator());
            mapper.updateById(cur);
        }
    }

    /** 脏 JSON 不该让整页打不开：{@link Json#read} 内部已做回落。 */
    private <T> T parse(SysBizRule e, Class<T> type, T fallback) {
        return e == null ? fallback : Json.read(e.getRule(), type, fallback);
    }

    private String write(Object rule) {
        return Json.write(rule);
    }

    /** 三分区共用一个币种展示；以 WITHDRAW 为准（金额阈值最敏感的那个分区）。 */
    private static String currencyOf(SysBizRule... rows) {
        for (SysBizRule e : rows) {
            if (e != null && e.getCurrency() != null && !e.getCurrency().isBlank()) return e.getCurrency();
        }
        return DEFAULT_CURRENCY;
    }

    private static LocalDateTime latest(SysBizRule... rows) {
        LocalDateTime max = null;
        for (SysBizRule e : rows) {
            if (e == null || e.getUpdatedAt() == null) continue;
            if (max == null || e.getUpdatedAt().isAfter(max)) max = e.getUpdatedAt();
        }
        return max;
    }
}
