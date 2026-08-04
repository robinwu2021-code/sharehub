package ai.neargo.sharehub.agent.ext.service;

import java.util.List;

/**
 * 代理辖域的 CSV ↔ 行转换（[db-design §1.7] 多值列拆表）。
 *
 * <p>前端 {@code Agent.regionScope} 是逗号串，库里是 {@code agt_agent_region} 一行一辖区。
 * 转换集中在这里，**不要在 controller / 各 service 里手写 split** —— 分散写法必然在
 * 空串、尾逗号、空格、重复值这四件事上各出各的 bug。
 *
 * <p><b>接入点</b>：{@code agent/service/impl/AgentServiceImpl#save} 目前把
 * {@code regionScope} 原样存进 {@code agt_agent.region_scope} 列（CSV）。该文件属 agent 主包、
 * 不在本分片边界内，故此处只提供能力：主包接入后应在保存代理时调 {@link #replace}，
 * 列表出参时调 {@link #csvOf} 反查，{@code agt_agent.region_scope} 列退化为展示冗余。
 */
public interface AgentRegionService {

    /** 读某代理的辖区列表。 */
    List<String> regionsOf(String agentNo);

    /** 读某代理的辖区并拼回 CSV（供前端 {@code regionScope} 直出）。 */
    String csvOf(String agentNo);

    /**
     * 全量替换某代理的辖区（先软删旧行再插新行）。
     *
     * @param csv 逗号分隔的 region_id；null/空串表示清空辖区
     * @return 实际生效的辖区列表（已去重、去空白）
     */
    List<String> replace(String agentNo, String csv);
}
