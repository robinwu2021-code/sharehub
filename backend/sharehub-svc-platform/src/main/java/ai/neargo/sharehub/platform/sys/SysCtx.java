package ai.neargo.sharehub.platform.sys;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 系统配置域的两件小事：**当前隔离键**与**时间列格式**。
 *
 * <p>抽出来是因为 {@code sys_param}/{@code sys_login_setting}/{@code sys_tax_setting} 的自然键
 * 与 {@code tenant_id} 组成复合唯一 —— 每个按键查询都要拼同一个条件，
 * 散落各处迟早漏一个，漏一个就是跨租户串数据。
 */
public final class SysCtx {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    public static final String TENANT_MAIN = "MAIN";

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SysCtx() {
    }

    /** 当前隔离键：有运营端会话取会话上的，否则回落 MAIN（内部调用/初始化场景）。 */
    public static String tenantId() {
        return SecurityUtils.currentUser()
                .map(LoginUser::tenantId)
                .filter(t -> t != null && !t.isBlank())
                .orElse(TENANT_MAIN);
    }


    /** {@code LocalDateTime} → 出参字符串（时间列统一 String，避免序列化格式分歧）。 */
    public static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(FMT);
    }

    /** TINYINT(1) → Boolean（null 视为 false）。 */
    public static Boolean bool(Integer v) {
        return v != null && v == 1;
    }

    /** Boolean → TINYINT(1)，给默认值用。 */
    public static Integer flag(Boolean v) {
        return (v != null && v) ? 1 : 0;
    }
}
