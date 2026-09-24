package ai.neargo.sharehub.trade.order;

import java.util.Arrays;
import java.util.Optional;

/**
 * 押金单状态（{@code ord_deposit.status}）。
 *
 * <pre>
 *   HELD ──release──▶ RELEASED      订单结清，押金解冻
 *     └──buyout───▶ BOUGHT_OUT      超时未还，押金转买断（钱已计收入）
 *   ARREARS ──dun──▶ ARREARS        欠费催缴（次数 +1）
 * </pre>
 *
 * <h3>为什么收进枚举：这一列曾经写错过</h3>
 * 落库那一行写的是 {@code "BUYOUT"}，而<b>其余所有地方都是 {@code BOUGHT_OUT}</b> ——
 * DDL 列注释、{@code OrdDeposit} 的 javadoc、{@code DepositService} 的接口注释，
 * 以及运营端的 {@code DepositStatus} 联合类型与状态机。
 *
 * <p>后果不是「少个下划线」：买断之后那一行的状态<b>不在运营端的取值集里</b> ——
 * 按「买断」筛一条都查不到，状态徽标也映射不上。而两边都不报错。
 * 资金上没出事只是因为 {@code release()} 用的是正向白名单
 * （{@code if (!HELD) throw}），与别的状态怎么拼无关。
 *
 * <p>存量由 V64 归一。
 *
 * <h3>⚠️ ARREARS 目前没有任何代码会写</h3>
 * 它在 DDL 与各处注释里都有，但欠费那条链路还没落地。
 * 留在枚举里是因为它是词表的一部分（运营端也列着），<b>但别据此以为它会出现</b>。
 */
public enum DepositStatus {

    /** 已冻结。唯一可 release / buyout 的起点。 */
    HELD,
    /** 已解冻 —— 终态。 */
    RELEASED,
    /** 已买断，钱已计收入 —— 终态，不可解冻。 */
    BOUGHT_OUT,
    /** 欠费待追偿。⚠️ 目前没有代码会写入它，见类注释。 */
    ARREARS;

    public static Optional<DepositStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
