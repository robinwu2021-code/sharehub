package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.commons.query.PageRequest;
import ai.neargo.opskit.platform.core.app.DeliveryAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 事件投递的运维面（integration:manage）：查投递状态与每次尝试，修好回调后手工重投（21/06 §六）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class DeliveryController {

    private final DeliveryAppService deliveries;

    public DeliveryController(DeliveryAppService deliveries) {
        this.deliveries = deliveries;
    }

    /** @param state FAILED（重试耗尽，待人工）/ PENDING / DONE；空 = 全部 */
    public record DeliveryQuery(String state, String eventType, Integer page, Integer size) {
    }

    @PostMapping("/webhook-deliveries/query")
    public Page<DeliveryAppService.EventRow> query(Actor actor, @RequestBody(required = false) DeliveryQuery q) {
        DeliveryQuery x = q == null ? new DeliveryQuery(null, null, null, null) : q;
        return deliveries.query(actor, x.state(), x.eventType(), PageRequest.of(x.page(), x.size()));
    }

    @GetMapping("/webhook-deliveries/{id}")
    public DeliveryAppService.Detail get(Actor actor, @PathVariable("id") long id) {
        return deliveries.get(actor, id);
    }

    @PostMapping("/webhook-deliveries/{id}/redeliver")
    public DeliveryAppService.EventRow redeliver(Actor actor, @PathVariable("id") long id) {
        return deliveries.redeliver(actor, id);
    }
}
