package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.crud.BizNoSequence;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberPlanReq;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberPlanRow;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.PlanRightsVO;
import ai.neargo.sharehub.user.asset.entity.MbrPlan;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.MbrPlanMapper;
import ai.neargo.sharehub.user.asset.service.MemberPlanService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员方案实现。
 *
 * <p><b>权益在这一层就落成结构化 JSON</b>（{@code {"rentDiscount":…,"freeMinutes":…,"depositFree":…}}）
 * —— 与 {@code LocalMemberRights} 读的形状同一份约定。此前这一列存的是**展示文案数组**，
 * 于是「租金九折」这句话在库里是一段文本，计价链无从解析，账单照收原价。
 */
@Service
public class MemberPlanServiceImpl implements MemberPlanService {

    private static final String TENANT_MAIN = "MAIN";
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";

    /** MONTHLY(包月) / TIMES(次卡) / RIGHTS(权益卡) —— 与 {@code MbrPlan#cardType} 的注释同一口径。 */
    private static final List<String> CARD_TYPES = List.of("MONTHLY", "TIMES", "RIGHTS");

    private final MbrPlanMapper mapper;
    private final JdbcTemplate jdbc;

    public MemberPlanServiceImpl(MbrPlanMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<MemberPlanRow> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<MbrPlan> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(MbrPlan::getPlanNo, keyword).or().like(MbrPlan::getName, keyword)
                    .or().like(MbrPlan::getNameEn, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(MbrPlan::getStatus, status);
        w.orderByAsc(MbrPlan::getSortNo).orderByDesc(MbrPlan::getId);

        Page<MbrPlan> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(MemberPlanServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public MemberPlanRow save(MemberPlanReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.badRequest("error.common.missing_parameter", "name");
        }
        if (req.cardType() == null || !CARD_TYPES.contains(req.cardType())) {
            throw BizException.badRequest("error.member.plan_card_type_invalid", String.valueOf(req.cardType()));
        }
        requireSaneRights(req.rights());

        MbrPlan e = req.planNo() == null || req.planNo().isBlank() ? null : find(req.planNo());
        boolean create = e == null;
        if (create) {
            e = new MbrPlan();
            e.setTenantId(TENANT_MAIN);
            e.setPlanNo(BizNoSequence.next(jdbc, "mbr_plan", "plan_no", BizKey.MEMBER_PLAN, 4));
            // 新建默认下架 —— 建好就对 C 端可见，会让一个还没填完权益的方案直接开卖
            e.setStatus(DISABLED);
        }
        e.setName(req.name());
        e.setNameEn(req.nameEn());
        e.setNameAr(req.nameAr());
        e.setCardType(req.cardType());
        e.setPrice(req.price());
        e.setCurrency(req.currency());
        e.setPeriodDays(req.periodDays());
        e.setTimesTotal(req.timesTotal());
        e.setRights(rightsJson(req.rights()));
        e.setAutoRenew(Boolean.TRUE.equals(req.autoRenew()) ? 1 : 0);
        e.setSortNo(req.sortNo() == null ? 0 : req.sortNo());

        if (create) mapper.insert(e);
        else mapper.updateById(e);
        return toVO(e);
    }

    @Override
    public MemberPlanRow setEnabled(String planNo, boolean enable) {
        MbrPlan e = find(planNo);
        if (e == null) throw BizException.notFound(planNo);
        /*
         * 上架前要求权益可读。一个 rights 为空的方案上架后，C 端看到的是
         * 「有价格、没权益」的卡片，而买下去什么也不会发生 —— 那比不上架糟得多。
         */
        if (enable && rightsOf(e.getRights()) == null) {
            throw BizException.conflict("error.member.plan_rights_missing", planNo);
        }
        e.setStatus(enable ? ENABLED : DISABLED);
        mapper.updateById(e);
        return toVO(e);
    }

    private MbrPlan find(String planNo) {
        if (planNo == null || planNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<MbrPlan>()
                .eq(MbrPlan::getPlanNo, planNo).last("limit 1"));
    }

    /**
     * 权益取值合理性。
     *
     * <p>折扣率 ≤ 0 或 > 1 一律拒：{@code 0} 是免费、负数是倒贴、{@code 9} 是九倍价 ——
     * 三种都算得出金额、都不报错。<b>「九折」填成 {@code 9} 是最容易犯的那一种</b>
     * （见 {@code ChargeChain.Member} 的注释：折扣率不是折扣额，也不是「几折」这个整数）。
     */
    private static void requireSaneRights(PlanRightsVO r) {
        if (r == null) return;
        BigDecimal d = r.rentDiscount();
        if (d != null && (d.signum() <= 0 || d.compareTo(BigDecimal.ONE) > 0)) {
            throw BizException.badRequest("error.member.plan_discount_range", d.toPlainString());
        }
        if (r.freeMinutes() != null && r.freeMinutes() < 0) {
            throw BizException.badRequest("error.member.plan_free_minutes_range",
                    String.valueOf(r.freeMinutes()));
        }
    }

    /** 全空则存 null，不存 {@code {}} —— 空对象与「没配权益」在读侧要是同一件事。 */
    private static String rightsJson(PlanRightsVO r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (r.rentDiscount() != null) m.put("rentDiscount", r.rentDiscount());
        if (r.freeMinutes() != null && r.freeMinutes() > 0) m.put("freeMinutes", r.freeMinutes());
        if (Boolean.TRUE.equals(r.depositFree())) m.put("depositFree", true);
        return m.isEmpty() ? null : Json.write(m);
    }

    /** 与 {@code MembershipServiceImpl#rightsOf} / {@code LocalMemberRights#fromPlan} 同一份约定。 */
    private static PlanRightsVO rightsOf(String rights) {
        if (rights == null || rights.isBlank()) return null;
        Map<?, ?> r = Json.read(rights, Map.class, null);
        if (r == null) return null;
        Object d = r.get("rentDiscount");
        Object f = r.get("freeMinutes");
        return new PlanRightsVO(d instanceof Number n ? new BigDecimal(n.toString()) : null,
                f instanceof Number n ? n.intValue() : 0,
                Boolean.TRUE.equals(r.get("depositFree")));
    }

    private static MemberPlanRow toVO(MbrPlan e) {
        return new MemberPlanRow(e.getPlanNo(), e.getName(), e.getNameEn(), e.getNameAr(),
                e.getCardType(), e.getPrice(), e.getCurrency(), e.getPeriodDays(), e.getTimesTotal(),
                rightsOf(e.getRights()), e.getAutoRenew() != null && e.getAutoRenew() != 0,
                e.getSortNo(), e.getStatus());
    }
}
