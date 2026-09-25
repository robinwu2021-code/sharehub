package ai.neargo.sharehub.api.core.port;

import java.time.LocalDateTime;

/**
 * 交易自愈动作（业务告警 AUTO_FIX）。按订单号 + 状态条件更新实现幂等：订单已不在异常态时直接返回成功。
 * 资金动作（释放预授权 / 退款）由实现内部经支付接入完成，返回只代表已受理。
 */
public interface TradeRemedyPort {

    /** 撤销出宝失败的订单。返回 true = 已撤销或已不在异常态（无需再处理）。 */
    boolean cancelUndelivered(String orderNo, String reason);

    /** 按宝首次在柜中出现的时刻结单并计费。返回 true = 已结单或已不在进行中。 */
    boolean finishAt(String orderNo, String cabinetNo, Integer slotIndex, LocalDateTime returnedAt);
}
