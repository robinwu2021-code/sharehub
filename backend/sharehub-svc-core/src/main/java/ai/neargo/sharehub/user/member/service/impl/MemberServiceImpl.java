package ai.neargo.sharehub.user.member.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberBenefit;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCard;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCardGrantReq;
import ai.neargo.sharehub.user.member.entity.MbrBenefit;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;
import ai.neargo.sharehub.user.member.mapper.MbrBenefitMapper;
import ai.neargo.sharehub.user.member.service.MemberService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 会员权益与会员卡实现。
 *
 * <p>本类唯一有分量的逻辑是 {@link #assertMonotonic} —— 见其注释。
 */
@Service
public class MemberServiceImpl implements MemberService {

    /**
     * 等级由低到高 —— <b>全站唯一定义</b>，权益单调性校验与页面排序共用这一份。
     * 写成两份的话，改了一处忘了另一处，校验和展示就会各说各话。
     */
    private static final List<String> LEVELS = List.of("SILVER", "GOLD", "PLATINUM");

    private static final String TENANT_MAIN = "MAIN";

    private final MbrBenefitMapper mapper;
    /** 会员卡就是 {@code usr_membership} 的另一个投影；本服务只读它，写入在开通/续费流程里。 */
    private final ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrMembershipMapper memberships;

    public MemberServiceImpl(MbrBenefitMapper mapper,
                             ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrMembershipMapper memberships) {
        this.mapper = mapper;
        this.memberships = memberships;
    }

    @Override
    public List<MemberBenefit> benefits() {
        return mapper.selectList(null).stream()
                .sorted(Comparator.comparingInt(b -> levelIndex(b.getLevel())))
                .map(MemberServiceImpl::toVO)
                .toList();
    }

    @Override
    @Transactional
    public MemberBenefit saveBenefit(String level, MemberBenefit in) {
        if (levelIndex(level) < 0) {
            throw new IllegalArgumentException("未知会员等级: " + level + "，已知: " + LEVELS);
        }
        MbrBenefit e = mapper.selectOne(new LambdaQueryWrapper<MbrBenefit>()
                .eq(MbrBenefit::getLevel, level).last("limit 1"));
        boolean isNew = e == null;
        if (isNew) {
            e = new MbrBenefit();
            e.setLevel(level);
            e.setTenantId(TENANT_MAIN);
        }
        e.setName(in.name());
        e.setRentDiscount(in.rentDiscount());
        e.setFreeMinutes(in.freeMinutes());
        e.setDepositFree(Boolean.TRUE.equals(in.depositFree()) ? 1 : 0);
        e.setMonthlyCoupons(in.monthlyCoupons());
        e.setPointsRate(in.pointsRate());
        e.setUpgradePoints(in.upgradePoints());
        e.setStatus(in.status() == null ? "ENABLED" : in.status());

        // 把改动后的全量权益放在一起校验 —— 只看当前这一条无法判断单调性
        List<MbrBenefit> all = mapper.selectList(null).stream()
                .filter(x -> !level.equals(x.getLevel()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        all.add(e);
        assertMonotonic(all);

        if (isNew) mapper.insert(e);
        else mapper.updateById(e);
        return toVO(mapper.selectOne(new LambdaQueryWrapper<MbrBenefit>()
                .eq(MbrBenefit::getLevel, level).last("limit 1")));
    }

    /**
     * 强制「等级越高、权益越好」。
     *
     * <p><b>为什么必须在写入时拦</b>：黄金比铂金还便宜的话，会员体系当场失去意义 ——
     * 用户会买低等级卡拿高权益，且这种错误在列表页上不显眼（三行数字，谁会逐列对比？），
     * 等到有人发现时已经卖出去一批卡了。
     *
     * <p>三条单调性：折扣递减（更便宜）· 免费时长递增 · 升级门槛递增。
     * 其余字段（赠券数、积分率）不强制 —— 运营可能有意让某档不赠券。
     */
    private static void assertMonotonic(List<MbrBenefit> all) {
        List<MbrBenefit> sorted = all.stream()
                .sorted(Comparator.comparingInt(b -> levelIndex(b.getLevel())))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            MbrBenefit lo = sorted.get(i - 1), hi = sorted.get(i);
            if (nz(hi.getRentDiscount()).compareTo(nz(lo.getRentDiscount())) > 0) {
                throw new IllegalArgumentException(String.format(
                        "权益必须随等级变好：%s 的折扣(%s)不该高于 %s(%s)",
                        hi.getLevel(), hi.getRentDiscount(), lo.getLevel(), lo.getRentDiscount()));
            }
            if (nzi(hi.getFreeMinutes()) < nzi(lo.getFreeMinutes())) {
                throw new IllegalArgumentException(String.format(
                        "权益必须随等级变好：%s 的免费时长(%d)不该少于 %s(%d)",
                        hi.getLevel(), nzi(hi.getFreeMinutes()), lo.getLevel(), nzi(lo.getFreeMinutes())));
            }
            if (nzi(hi.getUpgradePoints()) < nzi(lo.getUpgradePoints())) {
                throw new IllegalArgumentException(String.format(
                        "升级门槛必须随等级递增：%s(%d) 不该低于 %s(%d)",
                        hi.getLevel(), nzi(hi.getUpgradePoints()), lo.getLevel(), nzi(lo.getUpgradePoints())));
            }
        }
    }

    /**
     * 会员卡列表 —— {@code usr_membership} 的投影。
     *
     * <p><b>此前这里无条件返回空页</b>，而上面那行注释（「本服务只读它」）读起来像是已经读了。
     * 两处因此一直是空的：运营端「会员卡」列表，以及<b>用户 360 档案</b>里的 cards ——
     * 后者更难发现，因为同一个响应里的「会员」块是真的（走 {@code MembershipService.get}），
     * 只有「会员卡」是空的，看起来像这个人没开过卡。
     *
     * <p>{@code keyword} 同时匹配卡号与用户号：360 档案就是把 {@code cUserNo} 当 keyword 传进来的。
     */
    @Override
    public PageResult<MemberCard> pageCards(Integer page, Integer size, String keyword, String level) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrMembership> w = new LambdaQueryWrapper<UsrMembership>()
                .eq(level != null && !level.isBlank(), UsrMembership::getLevel, level)
                .orderByDesc(UsrMembership::getId);
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(UsrMembership::getMbrNo, keyword).or().like(UsrMembership::getCUserNo, keyword));
        }
        Page<UsrMembership> r = memberships.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(MemberServiceImpl::toCard).toList(), r.getTotal());
    }

    private static MemberCard toCard(UsrMembership e) {
        return new MemberCard(e.getMbrNo(), e.getCUserNo(), e.getPlanNo(), e.getLevel(),
                e.getStartAt(), e.getEndAt(), e.getStatus(), e.getPoints(),
                Integer.valueOf(1).equals(e.getAutoRenew()));
    }

    @Override
    @Transactional
    public MemberCard grantCard(MemberCardGrantReq req) {
        throw new UnsupportedOperationException(
                "发卡需与支付/权益生效链路一起设计（M5），当前仅开放权益配置");
    }

    private static int levelIndex(String level) {
        return LEVELS.indexOf(level);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ONE : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }

    private static MemberBenefit toVO(MbrBenefit e) {
        return new MemberBenefit(e.getLevel(), e.getName(), e.getRentDiscount(), e.getFreeMinutes(),
                Integer.valueOf(1).equals(e.getDepositFree()), e.getMonthlyCoupons(),
                e.getPointsRate(), e.getUpgradePoints(), e.getStatus(),
                e.getUpdatedBy(), e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }
}
