package ai.neargo.sharehub.api.platform.event;

/**
 * 划拨对象的类型（{@code agt_assignment.target_type}，DDL 注释 {@code 'CABINET/LOCATION/SITE'}）。
 *
 * <p>放在 {@code sharehub-api} 而不是某个域里：它同时被**写入方**（platform 的划拨服务）
 * 与**消费方**（dev 的归属对账）使用，放进任一侧都会让另一侧跨域依赖实现。
 * api 模块本来就是跨域契约的所在。
 *
 * <p>⚠️ 三个值是**三种粒度**而非三个对象：划拨一个站点等于划拨它下面的全部点位与机柜。
 * 对账时按 {@link #CABINET} 逐台核对，是因为冗余归属列 {@code dev_cabinet.agent_no}
 * 落在机柜这一级（ADR-012）。
 */
public enum AssignTargetType {

    /** 单台机柜。 */
    CABINET,
    /** 点位（一个点位下可有多台机柜）。 */
    LOCATION,
    /** 站点（一个站点下可有多个点位）。 */
    SITE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static AssignTargetType of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("划拨对象类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("划拨对象类型非法: " + v + "（仅 CABINET/LOCATION/SITE）");
        }
    }
}
