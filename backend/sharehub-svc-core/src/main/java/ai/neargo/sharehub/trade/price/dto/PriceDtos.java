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
    public record PricePlanEntry(String planNo, String name, Integer freeMinutes, Integer unitMinutes,
                                 BigDecimal unitPrice, BigDecimal capDaily, BigDecimal buyoutPrice,
                                 String currency, String scope, String status, String archivedAt) {
    }

    /** 计费模板适用范围一行，镜像 {@code price_plan_scope}（[db-design §1.7]）。 */
    public record PlanScopeEntry(String planNo, String scopeType, String scopeRef) {
    }

    /**
     * 差异化定价行，镜像前端 {@code PricingDiff}。
     *
     * <p>字段名以前端收敛口径为准：{@code freeMinutes}(列 {@code free_mins})、
     * {@code capDaily}(列 {@code day_cap})。
     */
    public record PricingDiff(String ruleNo, String scene, String locationName, Integer freeMinutes,
                              BigDecimal unitPrice, BigDecimal capDaily, Integer priority, String currency,
                              String siteNo, String dimension, String matchRef) {
    }

    /**
     * 活动/时段价行，镜像前端 {@code PricingSchedule}。
     *
     * <p>{@code multiplier} 是**倍率**（可 &gt; 1），不是 0..1 的比率。
     */
    public record PricingSchedule(String ruleNo, String name, String period,
                                  BigDecimal multiplier, boolean active) {
    }

    /** 模板 + 其拆表范围的组合视图（详情抽屉用）。 */
    public record PricePlanDetail(PricePlanEntry plan, List<PlanScopeEntry> scopes) {
    }
}
