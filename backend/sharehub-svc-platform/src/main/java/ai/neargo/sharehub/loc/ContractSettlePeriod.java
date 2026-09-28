package ai.neargo.sharehub.loc;

import java.util.Arrays;

/**
 * 合同结算周期（{@code loc_contract.settle_period}，DDL 注释 {@code 'MONTH/QUARTER'}）。
 *
 * <h2>这一列写错的后果是「保底不补差」，而且只留下一行 WARN</h2>
 * {@code AdjustmentServiceImpl} 出保底补差时判的是
 * {@code !"MONTH".equalsIgnoreCase(settlePeriod)} —— 不等于 MONTH 就**跳过**、
 * 只 {@code log.warn}。于是把 {@code MONTH} 写成 {@code MONTHLY} 的后果不是报错，
 * 是<b>场地方该拿的保底差额永远不生成</b>，而账面上一切正常。
 *
 * <p>原先这一列在 {@code ContractServiceImpl} 里是
 * {@code notBlank(d.settlePeriod()) ? d.settlePeriod() : "MONTH"} —— 只补默认值、不校验。
 * 「补默认值」与「校验传进来的值」是两件事：没传时填一个对的，传了错的照样存。
 */
public enum ContractSettlePeriod {

    /** 按月结算（DDL 默认值，也是补差逻辑唯一支持的周期）。 */
    MONTH,
    /** 按季结算：{@code AdjustmentServiceImpl} 目前不自动补差，需财务按合同周期手工处理。 */
    QUARTER;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ContractSettlePeriod of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("结算周期非法: " + v + "（仅 MONTH/QUARTER）"));
    }
}
