package ai.neargo.sharehub.dev;

/**
 * 设备在线状态（{@code dev_cabinet.online_status}）。
 *
 * <p>与 {@link CabinetStatus} 是**正交的两件事**，所以是两列也是两个枚举：
 * 一台 {@code DEPLOYED} 的机柜可以是 {@code OFFLINE}（断网），
 * 一台 {@code FAULT} 的机柜也可能还 {@code ONLINE}（能上报故障说明网是通的）。
 * 合成一列会让"故障且离线"无法表达 —— 而那恰恰是最需要区分的一种情况。
 */
public enum OnlineStatus {

    /** 在线。 */
    ONLINE,
    /** 离线（DDL 默认值 —— 新设备在心跳到达前一律按离线算）。 */
    OFFLINE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static OnlineStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("在线状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("在线状态非法: " + v + "（仅 ONLINE/OFFLINE）");
        }
    }
}
