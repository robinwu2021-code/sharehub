package ai.neargo.sharehub.portal.core.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * C 端订单出参（{@code /mp/trade/orders**}）。
 *
 * <p><b>为什么要另立一套，而不是继续复用 {@code TradeLegacyDtos.RentOrder}</b>：
 * 那个 record 同时服务运营端与 C 端，于是两边都不合身 ——
 * <ul>
 *   <li>C 端**缺**它要的：费用明细、状态时间线、借还两端的门店名；</li>
 *   <li>C 端**多**了不该给的：{@code waivedAmount}/{@code compensateAmount}/
 *       {@code ejectCount}/{@code lastEjectAt} 是运营干预统计。那个 record 自己的构造器注释
 *       写着「C 端出参不含运营干预统计」，但 {@code detailForConsumer} 走的是
 *       {@code detail()} → {@code enrich()}，**这四项其实一直在往 C 端返**。</li>
 * </ul>
 * 一个 DTO 两个受众的代价就是这样：注释里的约定和代码里的事实对不上，而两边都不报错。
 */
public final class MpTradeDtos {

    private MpTradeDtos() {
    }

    /**
     * 费用明细的一项。
     *
     * <p><b>给 {@code type} 不给 label</b>：文案要跟着界面语言走（中/英/阿），
     * 后端返一句中文，阿语界面上就是一句中文。类型码由端上映射成本地化文案。
     *
     * @param type RENT（租借费）/ WAIVE（减免，负数）/ COMPENSATE（赔偿）/ DEPOSIT（押金）
     */
    public record FeeItemVO(String type, BigDecimal amount) {
    }

    /**
     * 状态时间线的一步。
     *
     * <p><b>刻意不带 {@code operator}</b> —— {@code ord_event_log} 里那一列是员工号／系统名，
     * 属内部标识，不该出现在消费者的订单详情里。
     */
    public record OrderStepVO(String status, String at) {
    }

    /**
     * C 端订单（镜像 c-app {@code ConsumerOrder}）。
     *
     * <p>{@code estimatedFee}/{@code estimatedDurationMin}/{@code capped} <b>只有进行中的单才有</b>
     * （详情与「进行中订单」两处单条投影才带，列表不带）。它们回答的是
     * 「现在已经多少钱了」—— 而 {@code durationMin}/{@code feeAmount} 这两列
     * <b>只在归还那一刻写入</b>，进行中恒为 0，端上照着渲染就是一个不动的 0。
     *
     * <p>{@code fees}/{@code timeline} <b>只有详情才带</b>，列表恒为 null ——
     * 时间线要逐单查 {@code ord_event_log}，挂在列表上就是 N+1。
     * 用 null（而不是空数组）表达「这个投影没带」，与「确实一步都没有」区分开。
     */
    public record ConsumerOrderVO(String orderNo, String cUserNo, String status,
                                  String cabinetNo, String siteName,
                                  String returnCabinetNo, String returnSiteName,
                                  String powerbankNo, String locationName,
                                  String rentStartAt, String rentEndAt, Integer durationMin,
                                  BigDecimal feeAmount, BigDecimal depositAmount, String currency,
                                  List<FeeItemVO> fees, List<OrderStepVO> timeline,
                                  BigDecimal estimatedFee, Integer estimatedDurationMin, Boolean capped) {

        /**
         * 列表投影用：**不带试算**。
         *
         * <p>试算要按订单逐单跑一遍计价链（读快照、算封顶、查券），挂在列表上就是 N+1，
         * 而订单列表恰恰是最长的那个列表。列表里的进行中单由端上按 {@code rentStartAt}
         * 显示已用时长，不显示金额 —— 显示一个恒为 0 的金额比不显示更糟。
         */
        public ConsumerOrderVO(String orderNo, String cUserNo, String status,
                               String cabinetNo, String siteName,
                               String returnCabinetNo, String returnSiteName,
                               String powerbankNo, String locationName,
                               String rentStartAt, String rentEndAt, Integer durationMin,
                               BigDecimal feeAmount, BigDecimal depositAmount, String currency,
                               List<FeeItemVO> fees, List<OrderStepVO> timeline) {
            this(orderNo, cUserNo, status, cabinetNo, siteName, returnCabinetNo, returnSiteName,
                    powerbankNo, locationName, rentStartAt, rentEndAt, durationMin,
                    feeAmount, depositAmount, currency, fees, timeline, null, null, null);
        }
    }
}
