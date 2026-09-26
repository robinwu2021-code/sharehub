package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.agent.ext.service.AgentExitService;
import ai.neargo.sharehub.agent.ext.service.AgentExitService.AgentExit;
import ai.neargo.sharehub.api.common.Checklist;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代理清退（运营核心流程 F3）：发起即停用 → 收回资产 → 结清 → 关闭账号，每步门禁全过才能推进；
 * 以及运维月度考核的查询（F5）。
 */
@RestController
public class AgentExitController {

    public record ExitReq(String reason) {
    }

    private final AgentExitService exits;
    private final ai.neargo.sharehub.agent.ext.service.AgentOpsAssessmentService assessments;

    public AgentExitController(AgentExitService exits, ai.neargo.sharehub.agent.ext.service.AgentOpsAssessmentService assessments) {
        this.exits = exits;
        this.assessments = assessments;
    }

    /** 运维月度考核历史（F5）：达成率、在线率、客诉、被接管数与由此定出的运维分成系数。 */
    @GetMapping("/api/agent/agents/{agentNo}/ops-assessments")
    @PreAuthorize("@perm.can('agent:performance:read')")
    public java.util.List<ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment> assessments(@PathVariable String agentNo) {
        return assessments.history(agentNo);
    }

    @PostMapping("/api/agent/agents/{agentNo}/exit")
    @PreAuthorize("@perm.can('agent:agent:update')")
    public AgentExit start(@PathVariable String agentNo, @RequestBody ExitReq r) {
        return exits.start(agentNo, r == null ? null : r.reason());
    }

    /**
     * 该代理**当前在途的**清退单；没有则返回 null（不是 404 —— 「没有在清退」是正常状态，不是错误）。
     *
     * <p>此前清退单号只能从「发起」那一次的返回值里拿到：刷新页面、换个人看就再也找不到，
     * 运营端只好加一个「按单号查进度」的输入框让人手抄。
     */
    @GetMapping("/api/agent/agents/{agentNo}/exit")
    @PreAuthorize("@perm.can('agent:agent:read')")
    public AgentExit openExit(@PathVariable String agentNo) {
        return exits.openOf(agentNo);
    }

    @GetMapping("/api/agent/exits/{exitNo}")
    @PreAuthorize("@perm.can('agent:agent:read')")
    public AgentExit get(@PathVariable String exitNo) {
        return exits.get(exitNo);
    }

    @GetMapping("/api/agent/exits/{exitNo}/gate")
    @PreAuthorize("@perm.can('agent:agent:read')")
    public Checklist gate(@PathVariable String exitNo) {
        return exits.gate(exitNo);
    }

    @PostMapping("/api/agent/exits/{exitNo}/advance")
    @PreAuthorize("@perm.can('agent:agent:update')")
    public AgentExit advance(@PathVariable String exitNo) {
        return exits.advance(exitNo);
    }
}
