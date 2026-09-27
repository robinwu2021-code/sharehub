package ai.neargo.sharehub.user.member.port;

import ai.neargo.sharehub.api.core.port.MemberRightsPort;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.time.BizClock;
import ai.neargo.sharehub.user.asset.entity.MbrPlan;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.MbrPlanMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrMembershipMapper;
import ai.neargo.sharehub.user.member.entity.MbrBenefit;
import ai.neargo.sharehub.user.member.mapper.MbrBenefitMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** {@link MemberRightsPort} 的本地实现（单体内同进程，拆服务时换成远调即可）。 */
@Component
public class LocalMemberRights implements MemberRightsPort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalMemberRights.class);

    private static final String ACTIVE = "ACTIVE";
    private static final String ENABLED = "ENABLED";

    private final UsrMembershipMapper memberships;
    private final MbrBenefitMapper benefits;
    private final MbrPlanMapper plans;

    /** 会员有效期是**业务日历**上的日期，不能用服务器时区判。见 BizClock。 */
    private final BizClock clock;

    public LocalMemberRights(UsrMembershipMapper memberships, MbrBenefitMapper benefits,
                             MbrPlanMapper plans, BizClock clock) {
        this.memberships = memberships;
        this.benefits = benefits;
        this.plans = plans;
        this.clock = clock;
    }

    @Override
    public Rights rightsOf(String cUserNo) {
        if (cUserNo == null || cUserNo.isBlank()) return null;
        UsrMembership m = memberships.selectOne(new LambdaQueryWrapper<UsrMembership>()
                .eq(UsrMembership::getCUserNo, cUserNo)
                .orderByDesc(UsrMembership::getId).last("limit 1"));
        if (m == null || !ACTIVE.equals(m.getStatus()) || expired(m.getEndAt())) return null;

        Rights merged = better(fromLevel(m.getLevel()), fromPlan(m.getPlanNo()));
        // 等于没有的权益不返回 —— 见 Rights#isEmpty
        return (merged == null || merged.isEmpty()) ? null : merged;
    }

    /** 等级权益：{@code mbr_benefit}，按 {@code usr_membership.level}。 */
    private Rights fromLevel(String level) {
        if (level == null || level.isBlank()) return null;
        MbrBenefit b = benefits.selectOne(new LambdaQueryWrapper<MbrBenefit>()
                .eq(MbrBenefit::getLevel, level).eq(MbrBenefit::getStatus, ENABLED).last("limit 1"));
        if (b == null) return null;
        return new Rights(level, null, b.getRentDiscount(),
                nz(b.getFreeMinutes()), yes(b.getDepositFree()));
    }

    /**
     * 卡权益：{@code mbr_plan.rights}，**结构化 JSON**
     * （{@code {"rentDiscount":0.9,"freeMinutes":30,"depositFree":true}}）。
     *
     * <p>历史上这一列存的是**展示文案数组**，那种值在结构上不可能参与计价。
     * 读不成对象时按「无卡权益」处理并记 WARN —— <b>不能静默当成不打折就完事</b>：
     * 方案页上写着「每日首小时免费」而账单照收，是用户看得见、又说不出所以然的损失。
     */
    private Rights fromPlan(String planNo) {
        if (planNo == null || planNo.isBlank()) return null;
        MbrPlan p = plans.selectOne(new LambdaQueryWrapper<MbrPlan>()
                .eq(MbrPlan::getPlanNo, planNo).last("limit 1"));
        if (p == null || p.getRights() == null || p.getRights().isBlank()) return null;
        Map<?, ?> r = Json.read(p.getRights(), Map.class, null);
        if (r == null) {
            log.warn("会员方案权益不是结构化 JSON，本单按无卡权益计价 planNo={}", planNo);
            return null;
        }
        return new Rights(null, planNo, decimal(r.get("rentDiscount")),
                integer(r.get("freeMinutes")), Boolean.TRUE.equals(r.get("depositFree")));
    }

    /**
     * 逐项取更优：折扣取**更低**、免费时长取**更长**、免押取**或**。
     *
     * <p>不是「卡覆盖等级」—— 白银用户买了次卡，不该因此丢掉等级带来的折扣。
     * {@code level}/{@code planNo} 两个留痕位各保留自己的来源。
     */
    private static Rights better(Rights level, Rights plan) {
        if (level == null) return plan;
        if (plan == null) return level;
        BigDecimal d = min(level.rentDiscount(), plan.rentDiscount());
        return new Rights(level.level(), plan.planNo(), d,
                Math.max(level.freeMinutes(), plan.freeMinutes()),
                level.depositFree() || plan.depositFree());
    }

    /** null = 不打折 = 1，所以 null 在「取更低」里不能当 0。 */
    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * {@code end_at} 是 {@code yyyy-MM-dd} 字符串列（DATE 语义）。
     *
     * <p>解析不了时返回 <b>false（不过期）</b>：脏数据不该表现为「会员权益凭空消失」——
     * 那和券过期解析的口径是同一条（见 {@code LocalCouponUse#expired}）。
     */
    private boolean expired(String endAt) {
        if (endAt == null || endAt.isBlank()) return false;   // 空=不限期
        try {
            return LocalDate.parse(endAt.substring(0, Math.min(10, endAt.length())))
                    .isBefore(clock.today());
        } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            log.warn("会员有效期字面量无法解析，按不过期处理 endAt={}", endAt);
            return false;
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean yes(Integer v) {
        return v != null && v != 0;
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int integer(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
