package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ai.neargo.opskit.commons.security.PermissionGuard;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 流程定义发布（21/06 §定义）：按代号幂等，内容不变不出新版本；权限 process:manage 由应用服务判。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class ProcessDefinitionController {

    private final ProcessDefinitionAppService processes;

    private final ai.neargo.opskit.process.core.app.ProcessHistoryService history;

    public ProcessDefinitionController(ProcessDefinitionAppService processes, ai.neargo.opskit.process.core.app.ProcessHistoryService history) {
        this.history = history;
        this.processes = processes;
    }

    /**
     * 当前生效的原始定义（配置界面编辑用）：形状与 PUT 请求体一致，取回改完可原样提交。需 process:manage（或应用自身）；
     * 响应头 {@code X-Definition-Version} 是版本号（内容哈希）。
     */
    /** @param running 未结束的实例数（任何版本） */
    public record ProcessRow(String code, Map<String, String> name, String version, boolean enabled, boolean inherited, boolean fromTemplate,
            long publishedAtMs, long running) {
    }

    public record VersionRow(String version, long publishedAtMs, String channel, String publishedBy, String note, boolean current) {
    }

    /** 流程目录（process:manage）。fromTemplate = 审批模板编出来的：改它请改模板，不要直接 PUT。 */
    @GetMapping("/processes")
    public List<ProcessRow> list(Actor actor) {
        PermissionGuard.require(actor, "process:manage");
        return processes.list(actor.tenant()).stream().map(p -> new ProcessRow(p.code(), p.name() == null ? Map.of() : p.name().values(), p.version(),
                p.enabled(), p.inherited(), p.fromTemplate(), p.publishedAtMs(), history.openInstances(actor.tenant(), p.code()))).toList();
    }

    @GetMapping("/processes/{code}/versions")
    public List<VersionRow> versions(Actor actor, @PathVariable("code") String code) {
        PermissionGuard.require(actor, "process:manage");
        List<ai.neargo.opskit.process.store.dao.DefinitionDao.Row> rows = processes.versions(actor.tenant(), actor.tenant().workspaceId(), code);
        if (rows.isEmpty()) {
            rows = processes.versions(actor.tenant(), ai.neargo.opskit.commons.tenancy.WorkspaceId.TEMPLATE, code);
        }
        return rows.stream().map(r -> new VersionRow(r.version(), r.publishedAtMs(), r.channel(), r.publishedBy(), r.note(), r.latest())).toList();
    }

    /** 停用 = 不接受新实例，在途的照常走完（process:manage）。 */
    @PostMapping("/processes/{code}/disable")
    public ResponseEntity<Void> disable(Actor actor, @PathVariable("code") String code) {
        processes.setEnabled(actor, actor.tenant().workspaceId(), code, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/processes/{code}/enable")
    public ResponseEntity<Void> enable(Actor actor, @PathVariable("code") String code) {
        processes.setEnabled(actor, actor.tenant().workspaceId(), code, true);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/processes/{code}/definition")
    public ResponseEntity<String> definition(Actor actor, @PathVariable("code") String code) {
        PermissionGuard.require(actor, "process:manage");
        ai.neargo.opskit.process.api.definition.CompiledProcess p = processes.resolve(actor.tenant(), code).process();
        return ResponseEntity.ok().header("X-Definition-Version", p.version()).contentType(MediaType.APPLICATION_JSON)
                .body(ProcessDefCodec.toCanonicalJson(p.definition()));
    }

    @PutMapping("/processes/{code}")
    public DefinitionBodies.PublishDto publish(Actor actor, @PathVariable("code") String code, @RequestBody JsonNode body) {
        ProcessDef def = DefinitionBodies.parse(code, body, ProcessDefCodec::fromJson, ProcessDef::code, "process");
        ProcessDefinitionAppService.PublishResult r = processes.publish(actor, actor.tenant().workspaceId(), def, DefinitionBodies.note(body),
                DefinitionBodies.channel(actor));
        return new DefinitionBodies.PublishDto(r.version(), r.created());
    }
}
