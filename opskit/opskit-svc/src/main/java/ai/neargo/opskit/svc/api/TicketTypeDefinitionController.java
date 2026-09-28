package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
import ai.neargo.opskit.ticket.store.TicketDefCodec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.PermissionGuard;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 工单类型与选项集发布（21/06 §类型）：按代号幂等；权限 ticket-type:manage 由应用服务判。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class TicketTypeDefinitionController {

    private final TicketDefinitionAppService definitions;

    public TicketTypeDefinitionController(TicketDefinitionAppService definitions) {
        this.definitions = definitions;
    }

    /**
     * 当前生效的原始定义（配置界面编辑用）：三语名称、选项集引用、派单策略、SLA 引用都在，形状与 PUT 请求体一致。
     * 需 ticket-type:manage（或应用自身）；继承自账户模板的也返回（响应头 {@code X-Definition-Inherited: true}）。
     */
    @GetMapping("/ticket-types/{code}/definition")
    public ResponseEntity<String> definition(Actor actor, @PathVariable("code") String code) {
        PermissionGuard.require(actor, "ticket-type:manage");
        TicketDefinitionAppService.ResolvedType r = definitions.resolveType(actor.tenant(), code)
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "ticket type not found"));
        return ResponseEntity.ok().header("X-Definition-Version", r.version()).header("X-Definition-Inherited", String.valueOf(r.inherited()))
                .contentType(MediaType.APPLICATION_JSON).body(TicketDefCodec.toCanonicalJson(r.def()));
    }

    @PutMapping("/ticket-types/{code}")
    public DefinitionBodies.PublishDto publishType(Actor actor, @PathVariable("code") String code, @RequestBody JsonNode body) {
        TicketTypeDef def = DefinitionBodies.parse(code, body, TicketDefCodec::typeFromJson, TicketTypeDef::code, "ticket type");
        TicketDefinitionAppService.Published r = definitions.publishType(actor, actor.tenant().workspaceId(), def, DefinitionBodies.note(body),
                DefinitionBodies.channel(actor));
        return new DefinitionBodies.PublishDto(r.version(), r.created());
    }

    @PutMapping("/option-sets/{code}")
    public DefinitionBodies.PublishDto publishOptionSet(Actor actor, @PathVariable("code") String code, @RequestBody JsonNode body) {
        OptionSetDef def = DefinitionBodies.parse(code, body, TicketDefCodec::optionSetFromJson, OptionSetDef::code, "option set");
        TicketDefinitionAppService.Published r = definitions.publishOptionSet(actor, actor.tenant().workspaceId(), def, DefinitionBodies.note(body),
                DefinitionBodies.channel(actor));
        return new DefinitionBodies.PublishDto(r.version(), r.created());
    }
}
