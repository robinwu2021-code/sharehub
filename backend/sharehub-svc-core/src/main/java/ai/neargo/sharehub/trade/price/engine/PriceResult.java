package ai.neargo.sharehub.trade.price.engine;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计价结果：**逐项明细 + 合计**。
 *
 * <p>为什么必须逐项返回而不是只给一个数：电费与服务费要分列进发票与分润
 * （多数市场电费不可加价，服务费才是平台收入），账单也要能向用户解释「这钱怎么来的」。
 * 只返回合计，这些需求都得再算一遍。
 */
public record PriceResult(List<Line> lines, BigDecimal total, String currency) {

    /**
     * @param itemType   费用项类型
     * @param chargeQty  计费量（已扣免费额度）
     * @param units      计费步数（已按 rounding 舍入）
     * @param amount     本项金额（已应用封顶）
     * @param cappedBy   若被封顶，记录封顶值；否则 null —— 让「为什么少收了」可解释
     */
    public record Line(String itemType, BigDecimal chargeQty, BigDecimal units,
                       BigDecimal amount, BigDecimal cappedBy) {
    }
}
