package ai.neargo.sharehub.trade.price;

import java.util.Arrays;
import java.util.Optional;

/**
 * 收费方案的状态（{@code price_plan.status}，DDL 默认 {@code ACTIVE}）。
 *
 * <h3>这套值出过一次「配了没反应」的事故</h3>
 * 同一列曾在三处用了两套词：DDL 与 {@code PricePlanServiceImpl} 写 {@code ACTIVE}，
 * 而<b>计价引擎只选 {@code ENABLED}</b>。后果是运营新建一个收费方案，
 * 界面显示「启用」、列表里好端端列着，<b>但计价引擎永远选不中它</b> ——
 * 订单一律按兜底价计费，而且没有任何报错。当时没出事只是因为种子里那一行
 * 恰好写的是 {@code ENABLED}（V41 已归一并说明）。
 *
 * <h3>⚠️ 与 {@code price_plan_item.status} 不是同一套</h3>
 * 明细行的 DDL 默认是 {@code ENABLED}，<b>两张表不共享这个值域</b> ——
 * V41 特意只动了主表。别看到「都是状态」就顺手统一。
 */
public enum PricePlanStatus {

    ACTIVE,
    DISABLED;

    public static Optional<PricePlanStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
