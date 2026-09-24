package ai.neargo.sharehub.api.core.dto;

/**
 * 机柜摘要 —— core 暴露给其它服务的最小只读投影。
 *
 * <p>字段刻意只有归属判断需要的几个：编号、展示名、当前归属代理、所属站点。
 * <b>不放状态/电量/仓位</b> —— 那些是 core 自己的业务细节，
 * 放进跨服务 DTO 会让每次机柜模型演进都变成跨服务的兼容性问题。
 *
 * @param agentNo 当前归属代理；{@code null} = 平台直营
 */
public record CabinetBrief(String cabinetNo, String name, String agentNo, String siteNo,
                           /** 所属点位。与 siteNo/agentNo 同源（都取自机柜自己的冗余列），
                               供调用方把三者作为**一整套归属快照**写下去，而不是拼半套。 */
                           String locationNo) {
}
