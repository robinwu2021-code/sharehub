package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部确认回复（21/06 §实例 · 21/02 §8.4）：接入方收到 {@code instance.awaiting_confirmation}（令牌只在事件里）后做业务校验，
 * 用应用凭证回复。重复回复返回首次结论（{@code first=false}），不再触发迁移；令牌过期 / 已离开该状态 → 409 opskit.confirmation.expired。
 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class ConfirmationController {

    private final GateTimersAndConfirmations gates;

    public ConfirmationController(GateTimersAndConfirmations gates) {
        this.gates = gates;
    }

    /** @param result CONFIRM / REJECT（REJECT 须写 reason） */
    public record ReplyBody(String result, String reason) {
    }

    public record Replied(String instanceNo, String result, boolean first) {
    }

    @PostMapping("/confirmations/{token}")
    public Replied reply(Actor actor, @PathVariable("token") String token, @RequestBody ReplyBody body) {
        GateTimersAndConfirmations.Reply reply;
        try {
            reply = GateTimersAndConfirmations.Reply.valueOf(body == null ? "" : String.valueOf(body.result()));
        } catch (IllegalArgumentException e) {
            throw new OpsKitException(CommonErrors.VALIDATION, "result must be CONFIRM or REJECT", Map.of(),
                    List.of(new FieldError("result", "opskit.validation.one_of")));
        }
        GateTimersAndConfirmations.ReplyResult r = gates.reply(actor, token, reply, body.reason());
        return new Replied(r.instanceNo(), r.result(), r.first());
    }
}
