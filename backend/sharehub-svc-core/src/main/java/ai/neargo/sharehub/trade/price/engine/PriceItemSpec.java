package ai.neargo.sharehub.trade.price.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个费用项的计价规格（引擎的输入，与表结构解耦）。
 *
 * <p>用 record 而不是直接吃实体：引擎要能被**订单快照**驱动 ——
 * 订单落库时快照的是「展开后的结构」而非 {@code plan_no} 引用，改价不影响在途单
 * （[db-design] 既有纪律）。实体只是这份规格的一个来源。
 *
 * @param itemType TIME_FEE / ENERGY_FEE / SERVICE_FEE / IDLE_FEE / HOLD_FEE
 * @param metering MINUTE / KWH / COUNT
 * @param freeQty  免费额度，从计费量里先扣掉
 * @param unitQty  计费步长（每 N 分钟 / 每 N 度）
 * @param rounding 步数的舍入方式，**必须显式** —— 口径不同会导致对账长期对不平
 * @param ladders  阶梯，按 seq 升序；单段即无阶梯
 * @param capDaily 单项封顶；null = 不封顶
 */
public record PriceItemSpec(String itemType, String metering,
                            BigDecimal freeQty, BigDecimal unitQty, String rounding,
                            List<PriceLadderSpec> ladders, BigDecimal capDaily) {

    /** 一段阶梯。{@code toQty} 为 null 表示无上限。 */
    public record PriceLadderSpec(BigDecimal fromQty, BigDecimal toQty, BigDecimal unitPrice) {
    }
}
