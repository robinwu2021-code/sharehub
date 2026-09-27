package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberRow;
import ai.neargo.sharehub.user.asset.entity.MbrPlan;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.MbrPlanMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrMembershipMapper;
import ai.neargo.sharehub.user.asset.service.MembershipService;
import ai.neargo.sharehub.user.core.service.BizNoAllocator;
import ai.neargo.sharehub.user.core.service.NicknameLookup;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会员实现。{@code cardType} 不落在 {@code usr_membership} 上，
 * 由 {@code plan_no} 关联 {@code mbr_plan} 取 —— 卡型是方案属性，冗余到持有记录上会随方案改型漂移。
 */
@Service
public class MembershipServiceImpl implements MembershipService {

    private static final String TENANT_MAIN = "MAIN";

    private final UsrMembershipMapper mapper;
    private final MbrPlanMapper plans;
    private final NicknameLookup nicknames;

    public MembershipServiceImpl(UsrMembershipMapper mapper, MbrPlanMapper plans, NicknameLookup nicknames) {
        this.mapper = mapper;
        this.plans = plans;
        this.nicknames = nicknames;
    }

    @Override
    public PageResult<MemberRow> page(Integer page, Integer size, String keyword, String level) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrMembership> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrMembership::getMbrNo, keyword).or().like(UsrMembership::getCUserNo, keyword));
        }
        if (level != null && !level.isBlank()) w.eq(UsrMembership::getLevel, level);
        w.orderByDesc(UsrMembership::getId);

        Page<UsrMembership> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, String> nick = nicknames.byUserNos(
                r.getRecords().stream().map(UsrMembership::getCUserNo).toList());
        Map<String, String> cardTypes = cardTypes(
                r.getRecords().stream().map(UsrMembership::getPlanNo).toList());

        List<MemberRow> rows = r.getRecords().stream()
                .map(e -> toVO(e, nick.get(e.getCUserNo()), cardTypes.get(e.getPlanNo())))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public MemberRow get(String cUserNo) {
        UsrMembership e = findByUser(cUserNo);
        if (e == null) return null;
        // Arrays.asList 而非 List.of：planNo 可能为 null（自定义权益，无关联方案），List.of 会 NPE
        return toVO(e, nicknames.byUserNo(cUserNo), cardTypes(Arrays.asList(e.getPlanNo())).get(e.getPlanNo()));
    }

    @Override
    public MemberRow save(UsrMembership body) {
        if (body.getCUserNo() == null || body.getCUserNo().isBlank()) {
            throw BizException.badRequest("error.common.missing_parameter", "cUserNo");
        }
        UsrMembership current = findByUser(body.getCUserNo());
        if (current == null) {
            body.setMbrNo(BizNoAllocator.next(mapper, "mbr_no", BizKey.C_USER, UsrMembership::getMbrNo));
            body.setTenantId(TENANT_MAIN);
            if (body.getStatus() == null) body.setStatus("ACTIVE");
            mapper.insert(body);
        } else {
            body.setId(current.getId());
            body.setMbrNo(current.getMbrNo());
            body.setTenantId(current.getTenantId());
            mapper.updateById(body);
        }
        return get(body.getCUserNo());
    }

    private UsrMembership findByUser(String cUserNo) {
        return mapper.selectOne(new LambdaQueryWrapper<UsrMembership>()
                .eq(UsrMembership::getCUserNo, cUserNo)
                .orderByDesc(UsrMembership::getId)
                .last("limit 1"));
    }

    private Map<String, String> cardTypes(List<String> planNos) {
        List<String> keys = planNos.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
        if (keys.isEmpty()) return Map.of();
        return plans.selectList(new LambdaQueryWrapper<MbrPlan>().in(MbrPlan::getPlanNo, keys)).stream()
                .filter(x -> x.getCardType() != null)
                .collect(Collectors.toMap(MbrPlan::getPlanNo, MbrPlan::getCardType, (a, b) -> a));
    }

    private static MemberRow toVO(UsrMembership e, String nickname, String cardType) {
        return new MemberRow(e.getCUserNo(), nickname, e.getLevel(), e.getPoints(), cardType,
                e.getPlanNo(), e.getEndAt(), e.getStatus());
    }

    @Override
    public List<ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MembershipPlanVO> plansFor(String cUserNo) {
        UsrMembership mine = findByUser(cUserNo);
        return plans.selectList(new LambdaQueryWrapper<MbrPlan>()
                        .eq(MbrPlan::getStatus, "ENABLED").orderByAsc(MbrPlan::getSortNo)).stream()
                .map(p -> {
                    boolean active = mine != null && p.getPlanNo().equals(mine.getPlanNo())
                            && "ACTIVE".equals(mine.getStatus());
                    return new ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MembershipPlanVO(
                            p.getPlanNo(), p.getName(), p.getPrice(), p.getCurrency(), p.getCardType(),
                            rightsOf(p.getRights()), active, active ? mine.getEndAt() : null);
                }).toList();
    }

    /**
     * {@code mbr_plan.rights} 是**结构化 JSON**
     * （{@code {"rentDiscount":0.9,"freeMinutes":30,"depositFree":true}}，[TDD-会员套餐 §2]）。
     *
     * <p>解析不了返回 {@code null}（页面不显示权益行），**不兜底成一条文案** ——
     * 此前这里把整个脏值当成一条权益文本透出去，于是屏幕上会出现一段 JSON，
     * 或者更糟：一句「每日首小时免费」而计价链读不到任何折扣。
     * 权益读不出来就该是空的，让人看见缺失，而不是看见一句无人兑现的承诺。
     */
    private static ai.neargo.sharehub.user.asset.dto.UserAssetDtos.PlanRightsVO rightsOf(String rights) {
        if (rights == null || rights.isBlank()) return null;
        java.util.Map<?, ?> r = ai.neargo.sharehub.common.Json.read(rights, java.util.Map.class, null);
        if (r == null) return null;
        Object d = r.get("rentDiscount");
        Object f = r.get("freeMinutes");
        return new ai.neargo.sharehub.user.asset.dto.UserAssetDtos.PlanRightsVO(
                d instanceof Number n ? new java.math.BigDecimal(n.toString()) : null,
                f instanceof Number n ? n.intValue() : 0,
                Boolean.TRUE.equals(r.get("depositFree")));
    }
}
