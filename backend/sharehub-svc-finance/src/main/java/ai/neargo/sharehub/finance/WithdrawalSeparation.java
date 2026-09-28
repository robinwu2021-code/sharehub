package ai.neargo.sharehub.finance;

/**
 * 提现「审批人 ≠ 放款人」的执行强度。系统参数 {@code finance.withdrawal.separation}。
 *
 * <h2>为什么是三态而不是开关</h2>
 * 布尔只有「拦」和「不拦」，而真实处境常是第三种：
 * <b>知道它不合规，但现在没有第二个人，同时希望这件事被记录下来</b>。
 * 只给两态会逼人选 {@link #OFF}，于是「谁在什么时候自己给自己放的款」这条信息永远丢失。
 *
 * <h2>为什么不硬拦</h2>
 * 拦错了的后果是**钱打不出去**，而小团队只有一个财务是常态。
 * 本仓已经栽过一次同形的：合同双人复核的两道闸写得全对，
 * 但生产只有一个账号时任何合同都进不了 ACTIVE，只能靠改库自救。
 * 所以默认拦，但让部署方自己决定。
 */
public enum WithdrawalSeparation {

    /** 放款人不得是审批人；违反直接 409。**默认**，合规口径。 */
    REQUIRE,

    /** 允许，但记 WARN 且在单据上标记「同一人经办」。过渡期用：知道不合规，但暂时只有一个人。 */
    WARN,

    /** 不判。单人运营且明确接受 —— 注意**记录照旧**，关掉的只是拦截。 */
    OFF;

    /** 系统参数键。与 {@code SysConfigController} 的写入校验、运营台的配置项同一个常量。 */
    public static final String KEY = "finance.withdrawal.separation";

    /**
     * 解析参数值。**认不出来就当 {@link #REQUIRE}** —— 合规相关的配置，
     * 拼错一个字母不该变成「不检查」：那是把一道闸悄悄拆掉，而没有任何人会收到通知。
     */
    public static WithdrawalSeparation of(String raw) {
        if (raw == null || raw.isBlank()) return REQUIRE;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REQUIRE;
        }
    }
}
