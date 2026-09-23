package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.finance.ShareMode;
import ai.neargo.sharehub.finance.ShareRecordStatus;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.api.platform.dto.SiteSharingBrief;
import ai.neargo.sharehub.api.platform.port.SiteSharingQueryPort;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.ShareRuleMapper;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 订单 → 分润明细。**这是 MVP 三条硬阻塞里唯一没有外部依赖的一条**，补上之后
 * 「订单产生收入」与「谁该分到钱」之间才第一次有代码连着。
 *
 * <h2>费率取自哪里（运营管理清单 D2 的落地取舍）</h2>
 *
 * <ul>
 *   <li><b>VENUE 以进场合同为准</b>：合同是**按站点**签的、是双方签过字的那份；
 *       `share_rule` 按分成方配比例、没有站点维度，而同一个场地方在不同商场的分成
 *       完全可以不一样。没有生效合同时回落到 {@code share_rule(VENUE, venueNo)}，
 *       并在日志里说明用的是回落值。</li>
 *   <li><b>AGENT 只看 {@code share_rule(AGENT, agentNo)}</b>：代理分成本来就没有
 *       站点级约定。</li>
 * </ul>
 *
 * <h2>几条不显然的</h2>
 *
 * <ul>
 *   <li><b>基数是实收不是应收</b>：免单与券抵扣的部分没有真实现金流，
 *       按应收分账等于用平台的钱替用户给场地方付分成。基数由发布方带来。</li>
 *   <li><b>比例写快照</b>：规则或合同改了不重算历史分润 —— 这是财务口径，不是缓存。</li>
 *   <li><b>金额向下取整到分</b>：分账合计不得超过基数。四舍五入会让三方之和
 *       偶尔比收到的钱多一分，对账时这一分要查很久。</li>
 *   <li><b>幂等靠唯一键</b>（V37 {@code uk_srec_order_payee}）：outbox 重投是设计内的，
 *       撞键即跳过，不报错也不重复记账。</li>
 * </ul>
 */
@Service
public class ShareGeneratorImpl implements ShareGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShareGeneratorImpl.class);

    private final ShareRecordMapper records;
    private final ShareRuleMapper rules;
    private final SiteSharingQueryPort siteSharing;

    public ShareGeneratorImpl(ShareRecordMapper records, ShareRuleMapper rules,
                              SiteSharingQueryPort siteSharing) {
        this.records = records;
        this.rules = rules;
        this.siteSharing = siteSharing;
    }

    @Override
    public int generate(OrderSettledEvent e) {
        BigDecimal gross = e.grossAmount();
        if (gross == null || gross.signum() <= 0) {
            // 免单 / 全额券：没有真实收入，不该产生任何分成。**显式返回而不是算出 0 元记一笔** ——
            // 0 元明细会让结算单里出现一堆无意义的行，运营以为是漏算。
            return 0;
        }
        if (e.siteNo() == null || e.siteNo().isBlank()) {
            // 归属链断了（机柜没绑点位/站点）。这不是「没有分成」，是**数据缺陷**：
            // 这笔钱本该分给谁，现在没人知道。必须留痕，否则它会悄悄进平台口袋。
            log.warn("订单 {} 无站点归属（机柜 {}），跳过分润生成 —— 请检查机柜的点位/站点绑定",
                    e.orderNo(), e.cabinetNo());
            return 0;
        }

        String onDate = e.period() == null ? null : e.period() + "-01";
        SiteSharingBrief site = siteSharing.sharingOf(e.siteNo(), onDate);
        int n = 0;

        // ── 场地方 ──
        if (site != null && site.venueNo() != null && !site.venueNo().isBlank()) {
            BigDecimal rate = site.venueRate();
            String source = site.contractNo();
            if (rate == null) {
                ShareRule r = ruleOf("VENUE", site.venueNo());
                rate = r == null ? null : r.getRate();
                source = r == null ? null : r.getRuleNo();
                if (rate != null) {
                    log.info("站点 {} 没有生效合同，场地方分成回落到规则 {}（{}）",
                            e.siteNo(), source, rate);
                }
            }
            n += write(e, "VENUE", site.venueNo(), site.venueName(), rate, source, gross,
                    site.currency());
        }

        // ── 代理商 ──（空 = 平台直营，本来就不该有代理分成）
        if (e.agentNo() != null && !e.agentNo().isBlank()) {
            ShareRule r = ruleOf("AGENT", e.agentNo());
            n += write(e, "AGENT", e.agentNo(), r == null ? null : r.getPayeeName(),
                    r == null ? null : r.getRate(), r == null ? null : r.getRuleNo(), gross, null);
        }
        return n;
    }

    /**
     * 某分成方当前生效的规则。同一分成方可能配了多条，取 {@code priority} 最小的那条
     * （[db-design §5.4]「数值小者先命中」）；并列时按规则号兜底，保证补算两次结果一致。
     */
    private ShareRule ruleOf(String dimension, String payeeNo) {
        List<ShareRule> list = rules.selectList(new LambdaQueryWrapper<ShareRule>()
                .eq(ShareRule::getDimension, dimension)
                .eq(ShareRule::getPayeeNo, payeeNo));
        return list.stream()
                .min(Comparator.comparingInt((ShareRule r) -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority())
                        .thenComparing(r -> r.getRuleNo() == null ? "" : r.getRuleNo()))
                .orElse(null);
    }

    private int write(OrderSettledEvent e, String dimension, String payeeNo, String payeeName,
                      BigDecimal rate, String sourceNo, BigDecimal gross, String currency) {
        if (rate == null || rate.signum() <= 0) {
            // 没配比例 ≠ 比例为 0。前者是配置缺失（该有人去配），后者是明确的「不分」。
            // 记一条 0 元明细会把两者混为一谈，所以这里只告警不落库。
            log.warn("订单 {} 的 {} 分成方 {} 没有可用比例，未生成分润明细", e.orderNo(), dimension, payeeNo);
            return 0;
        }
        ShareRecord r = new ShareRecord();
        r.setRecordNo(FinNos.nextNo(records, "record_no", BizKey.SHARE_RECORD, 6));
        r.setOrderNo(e.orderNo());
        r.setDimension(dimension);
        r.setPayeeType(dimension);   // 冗余，结算单直接取用（见实体注释）
        r.setPayeeNo(payeeNo);
        r.setPayeeName(payeeName);
        r.setRate(rate);
        r.setGrossAmount(gross);
        // 向下取整到分：分账合计不得超过基数
        r.setAmount(gross.multiply(rate).setScale(2, RoundingMode.DOWN));
        r.setCurrency(currency == null ? e.currency() : currency);
        r.setMode(ShareMode.LEDGER.name());
        r.setStatus(ShareRecordStatus.PENDING.name());
        r.setPeriod(e.period());
        r.setSourceNo(sourceNo);
        try {
            records.insert(r);
            return 1;
        } catch (DuplicateKeyException dup) {
            // 事件重投。这是**正常路径**，不是错误 —— 唯一键正是为它准备的。
            log.debug("订单 {} 的 {} 分润已存在，跳过（事件重投）", e.orderNo(), dimension);
            return 0;
        }
    }
}
