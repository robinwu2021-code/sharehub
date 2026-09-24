package ai.neargo.sharehub.agent.ext.dto;

import java.math.BigDecimal;

/**
 * agent/ext 子域出参 VO 与动作入参。字段镜像 {@code ops-web/lib/types/agent.ts} 的同名 interface。
 *
 * <p>不往顶层 {@code dto/Dto.java} 追加（[骨架规约 §4]）。
 */
public final class AgentExtDtos {

    private AgentExtDtos() {
    }

    /**
     * 代理登录账号行，镜像前端 {@code AgentAccount}。
     *
     * <p>{@code dataScope} <b>不来自 {@code agt_account} 表</b>，而是
     * {@code iam_data_scope}({@code subject_type='AGENT_ACCOUNT'}, {@code subject_no=accountNo})
     * 的 {@code scope_type}（[db-design §1.7]）。iam 域的 mapper 不在本分片边界内，
     * 当前恒为 {@code null}，见 {@code AgentAccountServiceImpl} 的 TODO。
     */
    public record AgentAccount(String accountNo, String agentNo, String agentName,
                               String username, String loginPhone, String status,
                               String dataScope, String createdAt) {
    }

    /**
     * 划拨现状行，镜像前端 {@code AgentAssignment}（按代理聚合的归属统计）。
     *
     * <p>注意与 {@link AssignReq} 的区别：这是**现状**（谁手上有多少台/多少站），
     * {@code agt_assignment} 表存的是**流水**。
     */
    public record AgentAssignment(String agentNo, String agentName, String region,
                                  Integer cabinetCount, Integer siteCount) {
    }

    /** 划拨/收回入参。{@code action}=ASSIGN|REVOKE，{@code targetType}=CABINET|LOCATION|SITE。 */
    public record AssignReq(String agentNo, String targetType, String targetNo,
                            String action, String operator) {
    }

    /**
     * 划拨抽屉的候选资产，镜像前端 {@code AssignableAsset}。
     *
     * <p>带**当前归属**是刻意的：不标出来，运营会把别人名下的柜子误划走 ——
     * 划拨是覆盖式写入，误划之后原代理的数据范围里那台柜子就消失了。
     *
     * @param currentAgentNo {@code null} = 平台直营
     */
    public record AssignableAsset(String assetType, String assetNo, String name,
                                  String currentAgentNo, String currentAgentName) {
    }

    /**
     * 回收入参，镜像前端 {@code ReclaimAssetsPayload}。
     *
     * <p><b>刻意不带 {@code agentNo}</b> —— 从资产当前归属反查，杜绝
     * 「传错代理把别人的柜子收了」。这与 {@link AssignReq} 强制 {@code agentNo} 的方向相反，
     * 因为两者的风险不同：划拨传错代理会被「代理必须存在」挡下一部分，
     * 回收传错代理则会**静默收走无关资产**。
     */
    public record ReclaimReq(java.util.List<String> cabinetNos, java.util.List<String> siteNos,
                             String operatorName) {
    }

    /** 划拨流水行（{@code agt_assignment} 直出，供「划拨记录」抽屉）。 */
    public record AssignmentLog(String assignNo, String agentNo, String targetType, String targetNo,
                                String action, String operator, String createdAt) {
    }

    /**
     * 代理分润配置行，镜像前端 {@code AgentCommission}。
     *
     * <p>前端字段名是 {@code basis}，库列名是 {@code dimension} —— 此处按前端命名出参，
     * 转换只在这一层，库列名不动（[db-design §3.5]）。
     */
    public record AgentCommission(String ruleNo, String agentNo, String agentName, String basis,
                                  BigDecimal rate, BigDecimal fixedAmount, String currency,
                                  String mode, String effectiveAt, String status) {
    }

    /**
     * 代理绩效行（{@code [读] AgentPerformance}），镜像前端 {@code AgentPerformance}。
     *
     * <p>**不建物理表**：由 {@code agt_agent ⋈ ord_order}/{@code dev_cabinet} 聚合（[db-design §3.5]）。
     */
    public record AgentPerformance(String agentNo, String agentName, BigDecimal gmv,
                                   Integer cabinetCount, BigDecimal onlineRate,
                                   Integer rank, String currency) {
    }

}
