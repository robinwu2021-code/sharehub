package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
import ai.neargo.opskit.ticket.store.TicketDefCodec;
import org.springframework.web.bind.annotation.PathVariable;
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
