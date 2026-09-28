package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.store.ProcessDefCodec;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 流程定义发布（21/06 §定义）：按代号幂等，内容不变不出新版本；权限 process:manage 由应用服务判。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class ProcessDefinitionController {

    private final ProcessDefinitionAppService processes;

    public ProcessDefinitionController(ProcessDefinitionAppService processes) {
        this.processes = processes;
    }

    @PutMapping("/processes/{code}")
    public DefinitionBodies.PublishDto publish(Actor actor, @PathVariable("code") String code, @RequestBody JsonNode body) {
        ProcessDef def = DefinitionBodies.parse(code, body, ProcessDefCodec::fromJson, ProcessDef::code, "process");
        ProcessDefinitionAppService.PublishResult r = processes.publish(actor, actor.tenant().workspaceId(), def, DefinitionBodies.note(body),
                DefinitionBodies.channel(actor));
        return new DefinitionBodies.PublishDto(r.version(), r.created());
    }
}
