package ai.neargo.powerbank.portal.ops;

import ai.neargo.powerbank.agent.service.AgentService;
import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.Agent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * agt 域运营端端点（ADR-012 代理商）：薄控制器——路由 + @PreAuthorize + 调 Service。
 * 业务在 {@link AgentService}（MariaDB 持久化）；权限在注解，不散业务。
 */
@RestController
@RequestMapping("/api/agent/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Agent> agents(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String keyword) {
        return service.page(page, size, keyword);
    }

    @PostMapping({"", "/{agentNo}"})
    @PreAuthorize("@perm.can('agent:agent:update')")
    public Agent save(@PathVariable(required = false) String agentNo, @RequestBody Agent in) {
        return service.save(agentNo, in);
    }
}
