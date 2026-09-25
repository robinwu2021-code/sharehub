package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAccount;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAssignment;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentCommission;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentPerformance;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignReq;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignableAsset;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignmentLog;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.ReclaimReq;
import ai.neargo.sharehub.agent.ext.entity.AgtAccount;
import ai.neargo.sharehub.agent.ext.entity.AgtCommission;
import ai.neargo.sharehub.agent.ext.service.AgentAccountService;
import ai.neargo.sharehub.agent.ext.service.AgentAssignmentService;
import ai.neargo.sharehub.agent.ext.service.AgentCommissionService;
import ai.neargo.sharehub.agent.ext.service.AgentPerformanceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 代理商扩展端点（[api/README §六]）：代理账号 / 设备点位划拨 / 分润配置 / 代理绩效。
 *
 * <p>与 {@link AgentController} 同为 {@code /api/agent} 前缀但**子路径不重叠**
 * （那边独占 {@code /api/agent/agents}）。代理收益结算复用
 * {@code stl_settlement}(payee_type=AGENT)，菜单是跨域深链，本控制器不重复开端点。
 *
 * <p>控制器只做路由 + 鉴权 + 调 service。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentExtController {

    private final AgentAccountService accountService;
    private final AgentAssignmentService assignmentService;
    private final AgentCommissionService commissionService;
    private final AgentPerformanceService performanceService;

    public AgentExtController(AgentAccountService accountService,
                              AgentAssignmentService assignmentService,
                              AgentCommissionService commissionService,
                              AgentPerformanceService performanceService) {
        this.accountService = accountService;
        this.assignmentService = assignmentService;
        this.commissionService = commissionService;
        this.performanceService = performanceService;
    }

    // —— 代理账号管理（菜单叶：代理商管理 › 代理账号管理）——

    @GetMapping("/accounts")
    @PreAuthorize("@perm.can('agent:account:manage')")
    public PageResult<AgentAccount> accounts(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String agentNo,
                                             @RequestParam(required = false) String status) {
        return accountService.page(page, size, keyword,
                Map.of("agentNo", nz(agentNo), "status", nz(status)));
    }

    @GetMapping("/accounts/{accountNo}")
    @PreAuthorize("@perm.can('agent:account:manage')")
    public AgentAccount account(@PathVariable String accountNo) {
        return accountService.get(accountNo);
    }

    @PostMapping("/accounts")
    @PreAuthorize("@perm.can('agent:account:manage')")
    public AgentAccount createAccount(@RequestBody AgentExtDtos.AgentAccountReq body) {
        return accountService.save(body.toEntity());
    }

    @PostMapping("/accounts/{accountNo}")
    @PreAuthorize("@perm.can('agent:account:manage')")
    public AgentAccount updateAccount(@PathVariable String accountNo,
                                      @RequestBody AgentExtDtos.AgentAccountReq body) {
        AgtAccount e = body.toEntity();
        e.setAccountNo(accountNo); // 路径为准，忽略 body 里的键，防越权改他人账号
        return accountService.save(e);
    }

    // —— 设备/点位划拨（菜单叶：代理商管理 › 设备/点位划拨）——

    /**
     * 归属**现状**（每个代理手上有多少台柜/多少个站）。
     *
     * <p>流水查询走 {@code ?view=log}：现状是聚合读模型，流水是 {@code agt_assignment} 明细，
     * 两者形状不同，塞进一个响应会逼前端做联合类型判别。
     */
    @GetMapping("/assignments")
    @PreAuthorize("@perm.can('agent:scope:assign')")
    public PageResult<?> assignments(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String view,
                                     @RequestParam(required = false) String agentNo,
                                     @RequestParam(required = false) String targetType,
                                     @RequestParam(required = false) String targetNo) {
        if ("log".equalsIgnoreCase(view)) {
            PageResult<AssignmentLog> logs = assignmentService.page(page, size, agentNo, targetType, targetNo);
            return logs;
        }
        PageResult<AgentAssignment> rows = performanceService.assignments(page, size, keyword);
        return rows;
    }

    /** 划拨 / 收回，留痕到 {@code agt_assignment}（append）。 */
    @PostMapping("/assignments")
    @PreAuthorize("@perm.can('agent:scope:assign')")
    public AssignmentLog assign(@RequestBody AssignReq body) {
        return assignmentService.assign(body);
    }

    /**
     * 可划拨资产候选池（划拨抽屉的选项源）。
     *
     * <p>带**当前归属**返回，前端在选项上标注「已属某代理」—— 划拨是覆盖式写入，
     * 不标出来就会把别人名下的柜子误划走，而原代理只会发现资产凭空消失。
     *
     * <p>{@code agentNo} 传 {@code __NONE__} 只看平台直营。
     */
    @GetMapping("/assignable-assets")
    @PreAuthorize("@perm.can('agent:scope:assign')")
    public java.util.List<AssignableAsset> assignableAssets(@RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) String agentNo,
                                                            @RequestParam(required = false) String assetType,
                                                            @RequestParam(required = false) Integer limit) {
        return assignmentService.assignable(keyword, agentNo, assetType, limit);
    }

    /**
     * 批量回收到平台直营。
     *
     * <p><b>入参刻意不带 {@code agentNo}</b>，归属由后端从资产现状反查 ——
     * 这解决了前端 {@code agent.ts} 记录的语义冲突（{@code ReclaimAssetsPayload} 无 agentNo，
     * 而 {@link #assign} 强制 agentNo）。选反查而不是让前端补传，是因为两者风险不对称：
     * 划拨传错代理有「代理必须存在」兜一部分，回收传错代理会**静默收走无关资产**。
     *
     * <p>动作枚举仍写 {@code REVOKE}（库里存量如此），前端适配层已做 REVOKE→RECLAIM 映射。
     */
    @PostMapping("/assignments/reclaim")
    @PreAuthorize("@perm.can('agent:scope:assign')")
    public java.util.List<AssignmentLog> reclaim(@RequestBody ReclaimReq body) {
        return assignmentService.reclaim(body);
    }

    // —— 分润配置（菜单叶：代理商管理 › 分润配置）——

    @GetMapping("/commissions")
    @PreAuthorize("@perm.can('agent:share:config')")
    public PageResult<AgentCommission> commissions(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String agentNo,
                                                   @RequestParam(required = false) String dimension,
                                                   @RequestParam(required = false) String status) {
        return commissionService.page(page, size, keyword,
                Map.of("agentNo", nz(agentNo), "dimension", nz(dimension), "status", nz(status)));
    }

    @GetMapping("/commissions/{ruleNo}")
    @PreAuthorize("@perm.can('agent:share:config')")
    public AgentCommission commission(@PathVariable String ruleNo) {
        return commissionService.get(ruleNo);
    }

    @PostMapping("/commissions")
    @PreAuthorize("@perm.can('agent:share:config')")
    public AgentCommission createCommission(@RequestBody AgtCommission body) {
        return commissionService.save(body);
    }

    @PostMapping("/commissions/{ruleNo}")
    @PreAuthorize("@perm.can('agent:share:config')")
    public AgentCommission updateCommission(@PathVariable String ruleNo, @RequestBody AgtCommission body) {
        body.setRuleNo(ruleNo);
        return commissionService.save(body);
    }

    // —— 代理绩效（菜单叶：代理商管理 › 代理绩效）——

    /** 读模型：{@code agt_agent ⋈ ord_order}/{@code dev_cabinet} 聚合，不落表。当前为空实现。 */
    @GetMapping("/performance")
    @PreAuthorize("@perm.can('agent:performance:read')")
    public PageResult<AgentPerformance> performance(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to) {
        return performanceService.page(page, size, keyword, from, to);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
