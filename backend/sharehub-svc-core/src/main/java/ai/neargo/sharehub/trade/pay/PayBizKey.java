package ai.neargo.sharehub.trade.pay;

/**
 * 支付域业务键前缀。
 *
 * <p><b>临时落点</b>：{@code common/BizKey}（[db-design §1.4.1] 的代码化）目前没有登记
 * {@code pay_order} / {@code pay_auth} / {@code pay_refund} 三张表的前缀 ——
 * 骨架阶段不改他人文件，故先在本域声明。**合并时应上移到 {@code BizKey} 并同步 db-design §1.4.1**，
 * 前缀一旦进了历史数据就改不回来，务必先确认与既有前缀不撞：
 * {@code PAY} / {@code PA} / {@code PRF} 与 {@code PB}(充电宝) / {@code PD}(差异化定价) /
 * {@code PS}(时段价) / {@code RFD}(业务退款审批单) 均不同。
 *
 * <p>注意 {@code PRF}(渠道退款引用) ≠ {@code RFD}(业务审批单 {@code ord_refund})，两表分工见
 * {@code PayRefund} 类注释。
 */
public final class PayBizKey {

    private PayBizKey() {
    }

    /** 支付引用 {@code pay_order.pay_no}。 */
    public static final String PAY_ORDER = "PAY";

    /** 免押编排 {@code pay_auth.auth_no}。 */
    public static final String PAY_AUTH = "PA";

    /** 渠道退款引用 {@code pay_refund.refund_no}。 */
    public static final String PAY_REFUND = "PRF";
}
