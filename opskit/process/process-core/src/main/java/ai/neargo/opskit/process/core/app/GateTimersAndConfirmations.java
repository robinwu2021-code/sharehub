package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.ErrorKey;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.event.AwaitingConfirmation;
import ai.neargo.opskit.process.api.model.SubjectRef;
import ai.neargo.opskit.process.store.dao.InstanceDao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 外部确认门与定时门（21/07 O2-6 · O2-7 · 21/02 §8.4 · §九）。两者都靠「门代次」防旧：
 * 每次迁移都让 gate_epoch + 1，离开过该状态，旧的令牌、旧的定时就比对不上、自然作废 —— 正确性不依赖「取消成功」。
 */
public class GateTimersAndConfirmations implements StatusHook {

    public static final String CONFIRM_TIMEOUT_TASK = "confirm-timeout";
    public static final String GATE_TIMER_TASK = "gate-timer";
    public static final ErrorKey CONFIRMATION_EXPIRED = new ErrorKey("opskit.confirmation.expired", 409);
    private static final String TIMEOUT = "TIMEOUT";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Reply { CONFIRM, REJECT }

    /** 回复结果：首次回复的结论；{@code first=false} 表示这是重复回复，返回的是首次结果。 */
    public record ReplyResult(String instanceNo, String result, boolean first) {
    }

    private final ProcessDefinitionAppService definitions;
    private final InstanceDao instances;
    private final Supplier<InstanceAppService> instanceService;
    private final TimerScheduler timers;
    private final EventPublisher events;
    private final Clock clock;
    private final TransactionOperations tx;

    public GateTimersAndConfirmations(ProcessDefinitionAppService definitions, InstanceDao instances, Supplier<InstanceAppService> instanceService,
            TimerScheduler timers, EventPublisher events, Clock clock, TransactionOperations tx) {
        this.definitions = definitions;
        this.instances = instances;
        this.instanceService = instanceService;
        this.timers = timers;
        this.events = events;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    @SystemAction("状态钩子：进入外部确认门 / 定时门")
    public void onEnter(HookContext ctx, StatusDef status) {
        WorkspaceId ws = ctx.tenant().workspaceId();
        long now = clock.millis();
        switch (status.gate()) {
            case GateDef.External ext -> {
                String token = "okc_" + randomToken();
                instances.setConfirmToken(ws, ctx.instanceNo(), ctx.gateEpoch(), sha256(token));
                Long expires = ext.timeout() == null ? null : now + ext.timeout().toMillis();
                if (expires != null) {
                    timers.schedule(CONFIRM_TIMEOUT_TASK, new TimerId(ws, ctx.instanceNo(), ctx.gateEpoch()), Instant.ofEpochMilli(expires));
                }
                InstanceDao.Row row = instances.find(ws, ctx.instanceNo()).orElseThrow();
                SubjectRef subj = row.subjectType() == null ? null : new SubjectRef(row.subjectType(), row.subjectRef(), row.subjectDisplay());
                events.publish(new AwaitingConfirmation(ctx.tenant(), ctx.instanceNo(), row.processCode(), status.code(), ext.topic(), token, subj,
                        ctx.fields(), ctx.gateEpoch(), expires, clock.instant()));
            }
            case GateDef.Timer timer -> timers.schedule(GATE_TIMER_TASK, new TimerId(ws, ctx.instanceNo(), ctx.gateEpoch()),
                    Instant.ofEpochMilli(now + timer.after().toMillis()));
            case null, default -> {
            }
        }
    }

    @Override
    @SystemAction("状态钩子")
    public void onLeave(HookContext ctx, StatusDef status) {
        if (status.gate() instanceof GateDef.External) {
            instances.clearUnansweredToken(ctx.tenant().workspaceId(), ctx.instanceNo());
        }
    }

    /**
     * 接入方回复外部确认（仅应用自身）。令牌不存在 / 已作废 → 409 expired；重复回复 → 返回首次结果，不再触发迁移。
     */
    @SystemAction("仅应用自身：接入方完成业务校验后回复")
    public ReplyResult reply(Actor actor, String token, Reply reply, String reason) {
        if (!actor.isAppItself()) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "confirmations are replied by the integrating system");
        }
        if (reply == Reply.REJECT && (reason == null || reason.isBlank())) {
            throw new OpsKitException(ProcessErrors.REASON_REQUIRED, "rejecting a confirmation needs a reason");
        }
        return tx.execute(s -> {
            TenantContext t = actor.tenant();
            InstanceDao.ConfirmTarget target = instances.lockByConfirmToken(t.workspaceId(), sha256(token == null ? "" : token))
                    .orElseThrow(() -> new OpsKitException(CONFIRMATION_EXPIRED, "confirmation token unknown or expired"));
            if (TIMEOUT.equals(target.confirmResult())) {
                throw new OpsKitException(CONFIRMATION_EXPIRED, "confirmation timed out");
            }
            if (target.confirmResult() != null) {
                return new ReplyResult(target.instanceNo(), target.confirmResult(), false);
            }
            GateDef.External gate = externalGate(t, target.instanceNo(), target.status())
                    .orElseThrow(() -> new OpsKitException(CONFIRMATION_EXPIRED, "instance left the confirmation status"));
            if (instances.recordConfirm(t.workspaceId(), target.instanceNo(), target.gateEpoch(), reply.name()) != 1) {
                throw new OpsKitException(CONFIRMATION_EXPIRED, "confirmation token expired");
            }
            instanceService.get().fireSystem(t, target.instanceNo(), reply == Reply.CONFIRM ? gate.onConfirmed() : gate.onRejected(),
                    "app:" + actor.app().orElse("?"), reason);
            return new ReplyResult(target.instanceNo(), reply.name(), true);
        });
    }

    /** 外部确认超时（定时触发）。代次不符或已回复 → 无操作。 */
    @SystemAction("定时触发")
    public boolean onConfirmTimeout(TenantContext t, String instanceNo, int gateEpoch) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Optional<InstanceDao.Row> row = instances.find(t.workspaceId(), instanceNo);
            if (row.isEmpty() || !row.get().open() || row.get().gateEpoch() != gateEpoch) {
                return false;
            }
            Optional<GateDef.External> gate = externalGate(t, instanceNo, row.get().status());
            if (gate.isEmpty() || gate.get().onTimeout() == null || instances.recordConfirm(t.workspaceId(), instanceNo, gateEpoch, TIMEOUT) != 1) {
                return false;
            }
            instanceService.get().fireSystem(t, instanceNo, gate.get().onTimeout(), "system", "confirmation timed out");
            return true;
        }));
    }

    /** 定时门到点（定时触发）。代次不符 = 期间离开过该状态，无操作。 */
    @SystemAction("定时触发")
    public boolean onGateTimer(TenantContext t, String instanceNo, int gateEpoch) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Optional<InstanceDao.Row> row = instances.find(t.workspaceId(), instanceNo);
            if (row.isEmpty() || !row.get().open() || row.get().gateEpoch() != gateEpoch) {
                return false;
            }
            CompiledProcess p = definitions.byDefinitionId(t, row.get().definitionId());
            if (!(p.status(row.get().status()).gate() instanceof GateDef.Timer timer)) {
                return false;
            }
            instanceService.get().fireSystem(t, instanceNo, timer.onElapsed(), "system", "timer elapsed");
            return true;
        }));
    }

    private Optional<GateDef.External> externalGate(TenantContext t, String instanceNo, String status) {
        InstanceDao.Row row = instances.find(t.workspaceId(), instanceNo).orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "instance not found"));
        if (!row.open() || !row.status().equals(status)) {
            return Optional.empty();
        }
        CompiledProcess p = definitions.byDefinitionId(t, row.definitionId());
        return p.status(status).gate() instanceof GateDef.External ext ? Optional.of(ext) : Optional.empty();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
