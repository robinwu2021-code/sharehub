package ai.neargo.sharehub.platform.org.service;

/**
 * 一次被审计的操作**成没成**（{@code iam_audit_log.outcome}）。
 *
 * <h3>为什么失败的也要记</h3>
 * 此前拦截器遇到 {@code >=400} 直接跳过，理由是「没做成的记进去会让『谁改了什么』失真」。
 * 顾虑是对的，结论反了：<b>被拒绝的操作恰恰是最该留痕的那一类</b> ——
 * 有人拿没权限的账号反复点某个危险操作，那是安全信号，
 * 而现在它不留任何痕迹，事后查「谁试过改分润规则」得到的答案是「没有人」。
 *
 * <p>正确做法不是不记，是记下来并标清楚成没成，让查询自己分开这两件事。
 */
public enum AuditOutcome {

    /** 2xx/3xx：改动已经生效。 */
    SUCCESS,
    /** 403：身份有效但没有这个权限 —— 安全信号，查「谁试过」时看的就是这些。 */
    DENIED,
    /** 其余 4xx/5xx：请求没做成（参数不合法、业务规则拒绝、服务端出错）。 */
    FAILED;

    /**
     * 由 HTTP 状态判定。
     *
     * <p>401 不会走到这里 —— 那时候还没有身份，审计记不出「谁」，
     * 它属于安全日志而非操作审计。
     */
    public static AuditOutcome ofStatus(int status) {
        if (status < 400) return SUCCESS;
        return status == 403 ? DENIED : FAILED;
    }
}
