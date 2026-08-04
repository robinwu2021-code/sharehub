package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.agent.service.AgentService;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    @PreAuthorize("@perm.can('agent:agent:read')")
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

    /**
     * 归档Agent。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/{no}/archive")
    @PreAuthorize("@perm.can('agent:agent:update')")
    public Object archiveAgent(@PathVariable String no) {
        return service.archive(no);
    }

    /** 取消归档Agent：清空时间戳，回到默认列表。 */
    @PostMapping("/{no}/unarchive")
    @PreAuthorize("@perm.can('agent:agent:update')")
    public Object unarchiveAgent(@PathVariable String no) {
        return service.unarchive(no);
    }
}
