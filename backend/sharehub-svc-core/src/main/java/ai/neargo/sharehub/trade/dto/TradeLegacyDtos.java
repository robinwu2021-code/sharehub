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
                           Integer ejectCount, String lastEjectAt,
                           // 券抵扣额。feeAmount 是**折后**应付，只有它答不出「券抵了多少」，
                           // 而费用明细要能逐项相加等于实付
                           java.math.BigDecimal couponAmount,
                           // 会员折扣额。同 couponAmount 的理由：feeAmount 是折后应付，
                           // 少了这一项，费用明细就加不出实付来（见 ConsumerOrderAssembler#fees）
                           java.math.BigDecimal memberDiscountAmount,
                           // 收款态：PAID 已收 / UNPAID 欠费；进行中为空（V134，C-BL-04）
                           String payStatus,
                           // —— 第 5 批（运营方原文 MON-01 / PAY-01 / RNT-03）——
                           // 商家与站点：订单列表按它们筛选，也要能看见
                           String agentNo, String agentName, String siteNo,
                           // 最高收费额 = 本单快照的总封顶（借出时定格）；null = 方案不封顶
                           java.math.BigDecimal maxCharge,
                           // 进行中订单的实时时长与已累计费用（列表请求那一刻试算，不落库）；非进行中为 null
                           Integer liveDurationMin, java.math.BigDecimal accruedFee,
                           // 支付单号与渠道（PAY-01）：收过款才有
                           String payNo, String payChannel) {

        /** 兼容旧 20 参调用：第 5 批新增字段缺省 null。 */
        public RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                         String powerbankNo, String locationName, String status,
                         String rentStartAt, String rentEndAt, Integer durationMin,
                         double feeAmount, double depositAmount, String currency,
                         java.math.BigDecimal waivedAmount, java.math.BigDecimal compensateAmount,
                         Integer ejectCount, String lastEjectAt, java.math.BigDecimal couponAmount,
                         java.math.BigDecimal memberDiscountAmount, String payStatus) {
            this(orderNo, cUserNo, cabinetNo, returnCabinetNo, powerbankNo, locationName, status,
                    rentStartAt, rentEndAt, durationMin, feeAmount, depositAmount, currency,
                    waivedAmount, compensateAmount, ejectCount, lastEjectAt, couponAmount, memberDiscountAmount,
                    payStatus, null, null, null, null, null, null, null, null);
        }

        /** 兼容旧 19 参调用：收款态缺省 null。 */
        public RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                         String powerbankNo, String locationName, String status,
                         String rentStartAt, String rentEndAt, Integer durationMin,
                         double feeAmount, double depositAmount, String currency,
                         java.math.BigDecimal waivedAmount, java.math.BigDecimal compensateAmount,
                         Integer ejectCount, String lastEjectAt, java.math.BigDecimal couponAmount,
                         java.math.BigDecimal memberDiscountAmount) {
            this(orderNo, cUserNo, cabinetNo, returnCabinetNo, powerbankNo, locationName, status,
                    rentStartAt, rentEndAt, durationMin, feeAmount, depositAmount, currency,
                    waivedAmount, compensateAmount, ejectCount, lastEjectAt, couponAmount, memberDiscountAmount, null);
        }

        /** 兼容旧 18 参调用：会员折扣额缺省 null。 */
        public RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                         String powerbankNo, String locationName, String status,
                         String rentStartAt, String rentEndAt, Integer durationMin,
                         double feeAmount, double depositAmount, String currency,
                         java.math.BigDecimal waivedAmount, java.math.BigDecimal compensateAmount,
                         Integer ejectCount, String lastEjectAt, java.math.BigDecimal couponAmount) {
            this(orderNo, cUserNo, cabinetNo, returnCabinetNo, powerbankNo, locationName, status,
                    rentStartAt, rentEndAt, durationMin, feeAmount, depositAmount, currency,
                    waivedAmount, compensateAmount, ejectCount, lastEjectAt, couponAmount, null);
        }

        /** 兼容旧 17 参调用：券抵扣额缺省 null。 */
        public RentOrder(String orderNo, String cUserNo, String cabinetNo, String returnCabinetNo,
                         String powerbankNo, String locationName, String status,
                         String rentStartAt, String rentEndAt, Integer durationMin,
                         double feeAmount, double depositAmount, String currency,
                         java.math.BigDecimal waivedAmount, java.math.BigDecimal compensateAmount,
                         Integer ejectCount, String lastEjectAt) {
            this(orderNo, cUserNo, cabinetNo, returnCabinetNo, powerbankNo, locationName, status,
                    rentStartAt, rentEndAt, durationMin, feeAmount, depositAmount, currency,
                    waivedAmount, compensateAmount, ejectCount, lastEjectAt, null);
        }

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

    /**
     * 进行中订单的实时试算（只读，不核销券、不扣免单额度）。
     *
     * <p><b>为什么需要它</b>：{@code ord_order.duration_min}/{@code fee_amount} 只在归还那一刻写入，
     * 进行中的单这两列恒为 0。于是 C 端「实时计时计费」在界面上是一个不动的 0，
     * 而用户唯一的判断方式是退出重进 —— 界面不报错，只是什么都没发生。
     *
     * <p><b>为什么不让端上自己乘单价</b>：封顶、分时倍率、券、免单额度、会员权益都在服务端，
     * 端上乘出来的是一个「差一点」的数字，而用户会拿它去对账单。差一点比不显示更糟。
     *
     * @param capped 已触及总封顶（买断价）—— 到这里费用不再增长，是 C-US-02 要的那句话；
     *               此前端上写的是一句恒显示的静态提示，没人知道到底到没到
     */
    public record FeeEstimate(int durationMin, BigDecimal payable, BigDecimal couponUsed,
                              BigDecimal waivedAmount, boolean capped, String currency) {
    }

    /** C 端借出回执：订单号 + 弹出的充电宝 + 网关指令号（弹仓为骨架）。 */
    public record RentResult(String orderNo, String powerbankNo, String commandId) {
    }
}
