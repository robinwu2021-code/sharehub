package ai.neargo.sharehub.platform.notify;

/**
 * 触达目标的脱敏工具（[api/README §1.6]「服务端出参即脱敏，不靠前端遮」）。
 *
 * <p>本域比"出参脱敏"更严一档：{@code notify_log.target} 与 {@code notify_blacklist.target}
 * 是 <b>存储即脱敏</b> —— 明文联系方式根本不入这两张表。原因是发送记录是运营人员每天翻的页面，
 * 一旦落明文，任何有 {@code system:notify_log:read} 的人就等于拿到了全量用户联系方式；
 * 明文只该活在 {@code pb_pii}，由脱敏接口按次授权访问。
 *
 * <p>因此**写库前**必须调 {@link #maskTarget}，而不是查询时再遮。
 */
public final class NotifyTargets {

    private NotifyTargets() {
    }

    /**
     * 目标脱敏：手机留**前 6 后 2**，邮箱留**首字母 + 域名**。
     *
     * <p>函数是**幂等**的：已含 {@code *} 的值原样返回。拉黑命中判定要拿脱敏值去比对
     * （表里存的就是脱敏值），若不幂等，二次脱敏会把 {@code +9715****12} 再遮一层而匹配不上。
     *
     * <pre>
     *   +971501234567  →  +97150*****67
     *   ali@neargo.ai  →  a***@neargo.ai
     * </pre>
     */
    public static String maskTarget(String target) {
        if (target == null || target.isBlank()) return target;
        String t = target.trim();
        if (t.indexOf('*') >= 0) return t; // 已脱敏，幂等返回

        int at = t.indexOf('@');
        if (at > 0) {
            // 邮箱：首字母 + *** + @域名（本地部分长度不外泄，固定三颗星）
            return t.charAt(0) + "***" + t.substring(at);
        }
        // 手机/推送 token：前 6 后 2
        if (t.length() <= 8) {
            return t.charAt(0) + "*".repeat(Math.max(1, t.length() - 1));
        }
        return t.substring(0, 6) + "*".repeat(t.length() - 8) + t.substring(t.length() - 2);
    }
}
