package ai.neargo.sharehub.trade.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * trade 域的骨架期 DTO（自顶层 {@code dto.Dto} 归位）。
 *
 * <p>名字带 Legacy 是刻意的：它们是骨架期的形状，字段与当下的实体并不完全对齐。
 * 留在顶层共享集合里会让 svc-core 被迫依赖 app —— 归位只解决依赖，**不代表它们已经定型**。
 */
public final class TradeLegacyDtos {

    private TradeLegacyDtos() {
    }

    /**
     * 订单行，镜像前端 {@code RentOrder}。{@code waivedAmount} 直取列；
     * {@code compensateAmount/ejectCount/lastEjectAt} 由 {@code ord_intervention} 时间轴派生
     * （补偿=compensate 金额合计，弹出=eject 次数/最近时刻）—— 不另存计数列，免得两处打架。
     */
    public record RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                           String powerbankNo, String locationName, String status,
                           String rentStartAt, String rentEndAt, Integer durationMin,
                           double feeAmount, double depositAmount, String currency,
                           java.math.BigDecimal waivedAmount, java.math.BigDecimal compensateAmount,
                           Integer ejectCount, String lastEjectAt) {

        /** 兼容旧 13 参调用，派生字段缺省 null（C 端出参不含运营干预统计）。 */
        public RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                         String powerbankNo, String locationName, String status,
                         String rentStartAt, String rentEndAt, Integer durationMin,
                         double feeAmount, double depositAmount, String currency) {
            this(orderNo, cUserNo, cabinetNo, returnCabinetNo, powerbankNo, locationName, status,
                    rentStartAt, rentEndAt, durationMin, feeAmount, depositAmount, currency,
                    null, null, null, null);
        }
    }

    /** C 端借出回执：订单号 + 弹出的充电宝 + 网关指令号（弹仓为骨架）。 */
    public record RentResult(String orderNo, String powerbankNo, String commandId) {
    }
}
