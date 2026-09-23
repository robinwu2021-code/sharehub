package ai.neargo.sharehub.api.platform.dto;

/**
 * 点位的归属链 —— platform 暴露给 core 的最小只读投影。
 *
 * <p>机柜的 {@code site_no} / {@code agent_no} 是**冗余列**（V9 的数据范围锚点），
 * 值恒等于其所在点位的站点与代理。建档时按这里返回的值回填，
 * 而不是让调用方各填各的 —— 三者互相矛盾之后，「这台柜子归谁」就没有答案了。
 *
 * @param agentNo 归属代理；{@code null} = 平台直营
 */
public record LocationOwnership(String locationNo, String locationName,
                                String siteNo, String siteName, String agentNo) {
}
