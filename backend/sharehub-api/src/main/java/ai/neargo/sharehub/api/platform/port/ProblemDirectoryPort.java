package ai.neargo.sharehub.api.platform.port;

/**
 * 常见问题字典的读取（platform.md 实现）—— 报障分流的依据。
 *
 * <p>为什么要有这个端口：分流规则存在 {@code md_problem.suggested_action}，
 * 而它归 platform 域；{@code svc-ops}（cs 在那里）**被 enforcer 明令禁止**依赖 platform。
 * 在此之前 cs 侧装的是一个「一律转人工」的桩，于是这张表从没被读过 ——
 * 运营在「常见问题」里改策略不会有任何效果，也不报错（2026-09-28 查实）。
 */
public interface ProblemDirectoryPort {

    /**
     * 这条问题的建议处置（{@code SELF_SERVICE} / {@code TO_CS} / {@code TO_REFUND} / {@code TO_WORKORDER}）。
     *
     * <p>问题不存在、已归档、或已下架（{@code status != ENABLED}）一律返回 {@code null} ——
     * 三种情况对调用方是同一件事：**没有可用的策略**，该走兜底。
     * 下架意味着运营不再想按它答复，但用户的诉求还在。
     */
    String suggestedActionOf(String problemNo);
}
