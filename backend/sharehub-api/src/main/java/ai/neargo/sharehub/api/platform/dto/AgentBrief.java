package ai.neargo.sharehub.api.platform.dto;

/**
 * 代理商名录条目 —— 只够「把 {@code agentNo} 显示成人能看懂的样子」。
 *
 * <p><b>刻意不是实体</b>：调用方（站点伙伴责任的展示）要的是名字和类型，
 * 不是代理商的分润比例、联系方式、状态。给整个实体等于让调用方能读到
 * 它不该关心的字段，下一步就会有人照着它写业务判断。
 *
 * @param agentNo   代理商编号
 * @param name      名称
 * @param agentType 登记类型；查不到时为 {@code null}
 */
public record AgentBrief(String agentNo, String name, AgentType agentType,
                         // 2026-09-25 追加：ENABLED / SUSPENDED —— 自动派单不派给已暂停的代理（E8）
                         String status,
                         // 批次 F3：清退单处于「结清中」—— 停用代理这时要能把钱提走，否则清退永远结不清
                         boolean settlingExit) {

    /** 兼容旧构造点。 */
    public AgentBrief(String agentNo, String name, AgentType agentType) {
        this(agentNo, name, agentType, null, false);
    }

    /** 兼容构造点（无清退信息）。 */
    public AgentBrief(String agentNo, String name, AgentType agentType, String status) {
        this(agentNo, name, agentType, status, false);
    }

    public boolean enabled() {
        return status == null || "ENABLED".equals(status);
    }
}
