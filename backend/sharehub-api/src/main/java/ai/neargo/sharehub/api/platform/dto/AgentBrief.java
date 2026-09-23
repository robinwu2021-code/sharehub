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
public record AgentBrief(String agentNo, String name, AgentType agentType) {
}
