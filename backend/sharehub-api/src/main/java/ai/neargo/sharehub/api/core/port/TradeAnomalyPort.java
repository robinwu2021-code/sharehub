package ai.neargo.sharehub.api.core.port;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 交易异常的系统读（业务告警 RENT_NOT_DELIVERED / RETURN_NOT_RECOGNIZED 的判定输入）。豁免数据范围。 */
public interface TradeAnomalyPort {

    /** 出宝中超过 minutes 分钟仍未出宝成功的订单（已扣款 / 预授权却没拿到宝）。 */
    List<OrderAnomaly> undelivered(int minutes, int limit);

    /** 进行中订单的宝已在某柜中被识别超过 minutes 分钟（归还事件丢了，用户还在被计费）。 */
    List<OrderAnomaly> returnUnrecognized(int minutes, int limit);

    /**
     * 单个订单的当前上下文（自愈器与客服单用）：用户、借出柜；若宝已在柜中被识别，cabinetNo / slotIndex / since
     * 取识别到的位置与时刻。订单不存在返回 null。
     */
    OrderAnomaly orderInfo(String orderNo);

    /**
     * @param since 异常开始时刻：出宝中的下单时刻 / 宝首次在柜中出现的时刻（JVM 本地时间）
     */
    record OrderAnomaly(String orderNo, String cUserNo, String siteNo, String cabinetNo, Integer slotIndex,
                        String powerbankNo, String agentNo, LocalDateTime since, BigDecimal amount) {
    }
}
