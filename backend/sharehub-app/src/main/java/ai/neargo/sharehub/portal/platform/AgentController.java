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

    /**
     * 新建代理商。
     *
     * <p><b>判 {@code :create} 而不是 {@code :update}</b>：权限清单 §代理商「增/改」本来就是
     * 两个码，而此前两条路合用一个映射、一律判 {@code :update} ——
     * 于是 {@code agent:agent:create} <b>在目录里列着、没有任何端点认它</b>，
     * 管理员勾了「代理商 增」以为开了权限，实际配了等于没配
     *（与 2026-09-26 收拾掉的三个工单码同一族，见 V116；该码在
     * {@code known-perm-ssot-gaps.txt} 里登记为「声明未强制」，本次销账）。
     *
     * <p>可达性核过：生产里持有写权限的只有 BD（{@code agent:*} 通配）与 ADMIN（{@code *}），
     * 两者都覆盖新码，拆开不缩小任何人的访问面。
     *
     * <p>编号由服务端取，请求体里的 {@code agentNo} 不作数（见 {@code AgentServiceImpl.save}）。
     */
    @PostMapping
    @PreAuthorize("@perm.can('agent:agent:create')")
    public Agent create(@RequestBody Agent in) {
        return service.save(null, in);
    }

    /** 修改代理商档案。编号以路径为准，防越权改别家。 */
    @PostMapping("/{agentNo}")
    @PreAuthorize("@perm.can('agent:agent:update')")
    public Agent update(@PathVariable String agentNo, @RequestBody Agent in) {
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
