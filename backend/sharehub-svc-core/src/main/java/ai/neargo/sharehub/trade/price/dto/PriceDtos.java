package ai.neargo.sharehub.trade.price.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * trade/price 子域出参 VO。字段镜像 ops-web {@code lib/types/pricing.ts}。
 *
 * <p>不往顶层 {@code dto/Dto.java} 追加（那份共享 DTO 已冻结）。
 */
public final class PriceDtos {

    private PriceDtos() {
    }

    /**
     * 计费模板行，镜像前端 {@code PricePlan}（{@code extends Archivable}）。
     *
     * <p>{@code buyoutPrice} 对应实体/列的 {@code capTotal}/{@code cap_total}（前端口径叫「买断价」）。
     * {@code scope} 是给列表展示的 CSV，由 {@code price_plan_scope} 拼回；权威值仍在拆表。
     *
     * <p>{@code archivedAt} 不可省：前端类型继承 {@code Archivable}，页面靠它区分「在用/已归档」。
     * 出参漏了它，归档行与在用行在页面上长得一模一样。
     */
    /**
     * 计费方案**写入参**（白名单）。
     *
     * <p>相比实体 {@code PricePlan} 少两个字段，都是**有专门入口的**：
     * <ul>
     *   <li>{@code archivedAt} —— 归档走 {@code /price-plans/{no}/archive|unarchive}
     *       （本仓契约禁止 delete*，软删一律走 archive）。从保存接口改它等于绕过那条路径，
     *       而 LocService 早就防着同一件事（{@code body.setArchivedAt(current.getArchivedAt())}）；</li>
     *   <li>{@code status} —— 运营端表单里没有这一项，beforeCreate 兜底 ACTIVE。</li>
     * </ul>
     *
     * <p>两者不在入参里 → 映射出的实体该字段为 null → MyBatis-Plus 的 updateById 不写它
     * → 原值保留。既安全，又不需要在 beforeUpdate 里逐个记得去锁。
     */
    public record PricePlanReq(String planNo, String name, Integer freeMinutes, Integer unitMinutes,
                               java.math.BigDecimal unitPrice, java.math.BigDecimal capDaily,
                               java.math.BigDecimal capTotal, String currency, String scope,
                               String deviceType) {
        /** 映射到实体。**status / archivedAt 有意不设**（见类注释）。 */
        public ai.neargo.sharehub.trade.price.entity.PricePlan toEntity() {
            var e = new ai.neargo.sharehub.trade.price.entity.PricePlan();
            e.setPlanNo(planNo);
            e.setName(name);
            e.setFreeMinutes(freeMinutes);
            e.setUnitMinutes(unitMinutes);
            e.setUnitPrice(unitPrice);
            e.setCapDaily(capDaily);
            e.setCapTotal(capTotal);
            e.setCurrency(currency);
            e.setScope(scope);
            e.setDeviceType(deviceType);
            return e;
        }
    }

    public record PricePlanEntry(String planNo, String name, Integer freeMinutes, Integer unitMinutes,
                                 BigDecimal unitPrice, BigDecimal capDaily, BigDecimal buyoutPrice,
                                 String currency, String scope, String status, String archivedAt) {
    }

    /**
     * 收费方案适用范围一行，镜像 {@code price_plan_scope} —— **取价的唯一依据**（ADR-028）。
     *
     * <p>2026-09-23 扩字段：此前只有 {@code (planNo, scopeType, scopeRef)}，是给界面看的展示行；
     * 现在取价引擎读的就是它，所以设备类型、过滤器、优先级、生效期都必须能编辑，
     * 否则「界面上能配的」少于「引擎会读的」，差额部分只能改库。
     *
     * @param scopeType DEVICE/LOCATION/SITE/VENUE/AGENT/SCENE/REGION/ALL，越靠前越具体
     */
    public record PlanScopeEntry(Long id, String planNo, String scopeType, String scopeRef,
                                 String deviceType, String vendorCode, String model, String brandNo,
                                 Integer priority, String effectiveFrom, String effectiveTo) {
    }

    /**
     * 活动/时段价行，镜像前端 {@code PricingSchedule}。
     *
     * <p>{@code multiplier} 是**倍率**（可 &gt; 1），不是 0..1 的比率。
     */
    /**
     * 分时调价规则入参。
     *
     * <p><b>不收实体</b>：实体的 {@code active} 是 {@code Integer}（0/1），
     * 而前端契约里它是 {@code boolean} —— Jackson 不会把 {@code true} 塞进 Integer，
     * 直接抛 → **保存分时规则必 500**，页面上只显示「服务器错误」。
     * 入参用自己的形状，0/1 的转换留在服务端。
     */
    public record PricingScheduleReq(String ruleNo, String regionId, String name, String period,
                                     String days, String timeFrom, String timeTo, String expr,
                                     BigDecimal multiplier, Boolean active) {
    }

    /**
     * 分时调价规则行。
     *
     * <p><b>结构化字段必须带出来</b>（V49 起）：判倍率读的是 {@code days/timeFrom/timeTo}，
     * 而 {@code period} 只是给人看的中文串。只回 period 的话，运营端的编辑抽屉拿不到
     * 真正生效的那几个值 —— 界面上是空的，一保存就把它们清掉，而倍率从此按空条件命中。
     *
     * @param days     生效星期 CSV，{@code 1}=周一…{@code 7}=周日；空 = 每天
     * @param timeFrom {@code HH:mm}；与 {@code timeTo} 同时为空 = 全天
     * @param timeTo   {@code HH:mm}；可跨零点（{@code 22:00-06:00} 合法）
     * @param expr     结构化表达式原文，排错时用
     */
    public record PricingSchedule(String ruleNo, String name, String period,
                                  String days, String timeFrom, String timeTo, String expr,
                                  BigDecimal multiplier, boolean active) {
    }

    /** 模板 + 其拆表范围的组合视图（详情抽屉用）。 */
    public record PricePlanDetail(PricePlanEntry plan, List<PlanScopeEntry> scopes) {
    }
}
