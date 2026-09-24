package ai.neargo.sharehub.auth;

/**
 * 操作从**哪个端**发起，落进审计 {@code iam_audit_log.client_code}。
 *
 * <h3>为什么需要它：realm 回答不了的那个问题</h3>
 * 运营端与代理端共用 {@code /api/**} 和同一套审计。一条「改了分润规则」的记录，
 * 只看 actor 分不清是<b>运营替代理改的</b>还是<b>代理自己改的</b> ——
 * 而这恰恰是结算争议时第一个被问到的。
 *
 * <h3>只从会话派生，绝不从请求头取</h3>
 * 最省事的做法是让前端传一个 {@code X-Client} 头。但<b>审计字段如果能被被审计方
 * 自己设置，比没有这个字段更糟</b> —— 它会让人以为那一列可信，而实际上改一个
 * 请求头就能伪造，且伪造出来的记录和真的长得一模一样。
 *
 * <p>所以只有 {@link #of(Realm)} 这一条入口：realm 是服务端发令牌时写进会话的，
 * 调用方碰不到。
 *
 * <h3>为什么不直接用 realm 的名字</h3>
 * 今天两者一一对应，但它们是两件事：<b>realm 是「你拿什么凭据认证」，
 * clientCode 是「你从哪个界面操作」</b>。把 STAFF 存进一个叫 client_code 的列里，
 * 读的人得先知道这层映射才看得懂。真要分叉（比如将来运营有独立的移动端），
 * 分叉点就在这个方法里，而不是散落在各处的字符串。
 */
public enum ClientCode {

    /** 运营台。 */
    OPS,
    /** 代理端。 */
    AGENT,
    /** C 端小程序 / App。 */
    MP;

    /**
     * 由会话身份域派生。
     *
     * <p>故意穷举、不写 {@code default} —— 将来加一个 realm，编译当场失败。
     * 写 default 的话新 realm 会悄悄被记成某个现有端，而审计里一条错误的来源
     * 比没有来源更难发现（它看起来是对的）。
     */
    public static ClientCode of(Realm realm) {
        return switch (realm) {
            case STAFF -> OPS;
            case AGENT -> AGENT;
            case CONSUMER -> MP;
        };
    }
}
