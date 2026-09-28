package ai.neargo.opskit.process.core.app;

import ai.neargo.opskit.commons.condition.ConditionEvaluator;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.jdbc.ConditionalUpdate;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.platform.api.calendar.BusinessCalendar;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.sla.SlaPolicyDef;
import ai.neargo.opskit.process.api.event.SlaChanged;
import ai.neargo.opskit.process.client.InstanceObserver;
import ai.neargo.opskit.process.core.port.Calendars;
import ai.neargo.opskit.process.store.dao.SlaClockDao;
import ai.neargo.opskit.process.store.dao.SlaClockDao.Row;
import ai.neargo.opskit.process.store.dao.TransitionLogDao;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SLA 计时（21/07 O2-8 · 21/02 §8.5）。挂在状态钩子上，与迁移同事务：起算 · 暂停 / 恢复 · 条件变化重算 · 停止；
 * 到期与预警由定时任务触发（{@code sla-due} / {@code sla-warn}，ID {@code ws:clockId:epoch}），另有兜底扫描。
 *
 * <p>口径：
 * <ul>
 *   <li>暂停期间不计时：恢复时把「暂停那一刻还剩的时长」从恢复时刻起重新铺开（有日历时按营业时间铺）。</li>
 *   <li>条件变化（如改优先级）：重新匹配目标矩阵，从原起算点加上已暂停时长重算到期（不从头算、也不白送暂停时间）。</li>
 *   <li>逾期后停止：保持 BREACHED 并记停止时刻；按时停止：MET；实例作废（CANCELLED 类别）时未逾期的记 CANCELLED。</li>
 *   <li>每次重排定时 epoch + 1；预警、逾期只改状态不改 epoch（同一代次的到期定时仍有效）。</li>
 * </ul>
 */
public class SlaTracker implements StatusHook {

    public static final String DUE_TASK = "sla-due";
    public static final String WARN_TASK = "sla-warn";
    private static final Logger log = LoggerFactory.getLogger(SlaTracker.class);

    /** 读侧：每个指标一项（TicketView.slaMetrics / TodoItem.sla 的来源）。 */
    public record MetricView(String clockId, String policyCode, String metricCode, LocalizedText name, String state, Long startedAtMs, Long dueAtMs,
            long pausedMs, Long breachedAtMs, Long stoppedAtMs) {
    }

    private final SlaPolicyAppService policies;
    private final SlaClockDao clocks;
    private final TransitionLogDao logs;
    private final Calendars calendars;
    private final TimerScheduler timers;
    private final EventPublisher events;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;
    private final List<InstanceObserver> observers;

    public SlaTracker(SlaPolicyAppService policies, SlaClockDao clocks, TransitionLogDao logs, Calendars calendars, TimerScheduler timers,
            EventPublisher events, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        this(policies, clocks, logs, calendars, timers, events, ids, clock, tx, List.of());
    }

    /** @param observers 定时触发的预警 / 逾期写回后通知（迁移、改字段引起的变化由 ObserverHook 统一通知） */
    public SlaTracker(SlaPolicyAppService policies, SlaClockDao clocks, TransitionLogDao logs, Calendars calendars, TimerScheduler timers,
            EventPublisher events, BizNoGenerator ids, Clock clock, TransactionOperations tx, List<InstanceObserver> observers) {
        this.observers = List.copyOf(observers);
        this.policies = policies;
        this.clocks = clocks;
        this.logs = logs;
        this.calendars = calendars;
        this.timers = timers;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
    }

    // ── 钩子 ────────────────────────────────────────────────

    @Override
    @SystemAction("状态钩子：实例创建时挂上 SLA 策略")
    public void onStarted(HookContext ctx, StatusDef initial, List<String> slaPolicies) {
        TenantContext t = ctx.tenant();
        long now = clock.millis();
        for (String code : slaPolicies.stream().distinct().toList()) {
            SlaPolicyAppService.Resolved p = policies.resolve(t, code)
                    .orElseThrow(() -> new OpsKitException(CommonErrors.VALIDATION, "unknown sla policy " + code, Map.of("code", code),
                            List.of(new ai.neargo.opskit.commons.error.FieldError("slaPolicies", "opskit.sla.unknown_policy"))));
            Optional<SlaPolicyDef.Target> row = match(p.def(), ctx.fields());
            for (SlaPolicyDef.Metric m : p.def().metrics()) {
                Duration target = row.map(r -> r.targets().get(m.code())).orElse(null);
                if (target == null) {
                    continue; // 这一情形下不考核该指标
                }
                Row c = new Row(t.accountId().value(), t.workspaceId().value(), ids.next("SC"), ctx.instanceNo(), code, p.version(), m.code(),
                        target.toMillis(), row.get().calendar(), "PENDING", null, null, null, 0, null, null, null, 0, 0);
                clocks.insert(t.workspaceId(), c);
                Row cur = c;
                if (m.startOrDefault().onCreate()) {
                    cur = save(t, cur, start(t, cur, p.def(), now));
                }
                applyPause(t, cur, m, initial, now);
            }
        }
    }

    @Override
    @SystemAction("状态钩子：迁移后起算 / 暂停 / 恢复 / 停止")
    public void onTransitioned(HookContext ctx, StatusDef from, StatusDef to, String code) {
        TenantContext t = ctx.tenant();
        long now = clock.millis();
        for (Row c : clocks.ofInstance(t.workspaceId(), ctx.instanceNo())) {
            SlaPolicyDef def = policies.atVersion(t, c.policyCode(), c.policyVersion());
            SlaPolicyDef.Metric m = metric(def, c.metricCode());
            Row cur = c;
            if ("PENDING".equals(cur.state()) && hits(m.startOrDefault(), to, code)) {
                cur = save(t, cur, start(t, cur, def, now));
            }
            if (cur.active()) {
                if (hits(m.stopOrDefault(), to, code) || to.category().isTerminal()) {
                    save(t, cur, stop(t, cur, now, to.category() == StatusCategory.CANCELLED));
                    continue;
                }
                applyPause(t, cur, m, to, now);
            } else if ("PENDING".equals(cur.state()) && to.category().isTerminal()) {
                save(t, cur, withState(cur, "CANCELLED", now));
            }
        }
    }

    @Override
    @SystemAction("状态钩子：字段变化后重新匹配目标矩阵")
    public void onFieldsChanged(HookContext ctx, StatusDef current) {
        TenantContext t = ctx.tenant();
        long now = clock.millis();
        for (Row c : clocks.ofInstance(t.workspaceId(), ctx.instanceNo())) {
            if (!"PENDING".equals(c.state()) && !"RUNNING".equals(c.state()) && !"WARNING".equals(c.state()) && !"PAUSED".equals(c.state())) {
                continue; // 已逾期 / 已停止的不因改字段翻案
            }
            SlaPolicyDef def = policies.atVersion(t, c.policyCode(), c.policyVersion());
            Optional<SlaPolicyDef.Target> row = match(def, ctx.fields());
            Duration target = row.map(r -> r.targets().get(c.metricCode())).orElse(null);
            String cal = row.map(SlaPolicyDef.Target::calendar).orElse(null);
            if (target == null || target.toMillis() == c.targetMs() && java.util.Objects.equals(cal, c.calendarCode())) {
                continue; // 新情形不考核它时保留原目标（不静默取消已在跑的考核）
            }
            Row retargeted = new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(),
                    target.toMillis(), cal, c.state(), c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), c.pausedMs(), c.pausedSinceMs(), c.breachedAtMs(),
                    c.stoppedAtMs(), c.epoch(), c.version());
            save(t, c, "PENDING".equals(c.state()) ? retargeted : recompute(t, retargeted, def, now));
        }
    }

    // ── 定时触发 ────────────────────────────────────────────

    @SystemAction("定时触发：到期")
    public boolean onDue(TenantContext t, String clockId, int epoch) {
        return Boolean.TRUE.equals(tx.execute(s -> clocks.find(t.workspaceId(), clockId)
                .filter(c -> c.epoch() == epoch && ("RUNNING".equals(c.state()) || "WARNING".equals(c.state())))
                .map(c -> {
                    breach(t, c, clock.millis());
                    observers.forEach(o -> o.onInstanceChanged(t, c.instanceNo()));
                    return true;
                }).orElse(false)));
    }

    @SystemAction("定时触发：预警")
    public boolean onWarn(TenantContext t, String clockId, int epoch) {
        return Boolean.TRUE.equals(tx.execute(s -> clocks.find(t.workspaceId(), clockId)
                .filter(c -> c.epoch() == epoch && "RUNNING".equals(c.state()))
                .map(c -> {
                    save(t, c, withState(c, "WARNING", null));
                    observers.forEach(o -> o.onInstanceChanged(t, c.instanceNo()));
                    return true;
                }).orElse(false)));
    }

    /** 兜底（21/02 §8.5）：调度丢了也不会有指标永远不逾期。@return 补触发的条数 */
    @SystemAction("定时兜底")
    public int reconcile(int limit) {
        long now = clock.millis();
        int n = 0;
        for (Row c : clocks.overdue(AccountId.UNKNOWN, now, limit)) {
            TenantContext t = new TenantContext(new AccountId(c.accountId()), new WorkspaceId(c.workspaceId()));
            boolean done = c.dueAtMs() != null && c.dueAtMs() <= now ? onDue(t, c.clockId(), c.epoch()) : onWarn(t, c.clockId(), c.epoch());
            n += done ? 1 : 0;
        }
        return n;
    }

    // ── 读 ──────────────────────────────────────────────────

    @SystemAction("读：随实例 / 工单详情返回，可见性由调用方先判")
    public List<MetricView> metrics(TenantContext t, String instanceNo) {
        return clocks.ofInstance(t.workspaceId(), instanceNo).stream().map(c -> view(t, c)).toList();
    }

    MetricView view(TenantContext t, Row c) {
        SlaPolicyDef.Metric m = metric(policies.atVersion(t, c.policyCode(), c.policyVersion()), c.metricCode());
        return new MetricView(c.clockId(), c.policyCode(), c.metricCode(), m.name(), c.state(), c.startedAtMs(), c.dueAtMs(), c.pausedMs(),
                c.breachedAtMs(), c.stoppedAtMs());
    }

    // ── 状态变化（都返回新行，由 save 条件写回）──────────────

    private Row start(TenantContext t, Row c, SlaPolicyDef def, long now) {
        Row r = new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), "RUNNING", now, null, null, 0, null, null, null, c.epoch() + 1, c.version());
        return schedule(t, withDue(t, r, def, now));
    }

    /** 从「起算点 + 已暂停时长」按目标重铺到期与预警（起算、条件变化共用）。 */
    private Row withDue(TenantContext t, Row r, SlaPolicyDef def, long now) {
        Optional<BusinessCalendar> cal = calendar(t, r.calendarCode());
        Instant base = Instant.ofEpochMilli(r.startedAtMs() + r.pausedMs());
        long due = plus(cal, base, Duration.ofMillis(r.targetMs())).toEpochMilli();
        Long warn = def.warnAtPercent() == null ? null
                : plus(cal, base, Duration.ofMillis(r.targetMs() * def.warnAtPercent() / 100)).toEpochMilli();
        String state = "PAUSED".equals(r.state()) ? "PAUSED" : warn != null && warn <= now ? "WARNING" : "RUNNING";
        return new Row(r.accountId(), r.workspaceId(), r.clockId(), r.instanceNo(), r.policyCode(), r.policyVersion(), r.metricCode(), r.targetMs(),
                r.calendarCode(), state, r.startedAtMs(), due, warn, r.pausedMs(), r.pausedSinceMs(), r.breachedAtMs(), r.stoppedAtMs(), r.epoch(), r.version());
    }

    private Row recompute(TenantContext t, Row c, SlaPolicyDef def, long now) {
        Row r = withDue(t, bump(c), def, now);
        if (!"PAUSED".equals(r.state()) && r.dueAtMs() <= now) {
            return breachedRow(r, now);
        }
        return "PAUSED".equals(r.state()) ? r : schedule(t, r);
    }

    private void applyPause(TenantContext t, Row c, SlaPolicyDef.Metric m, StatusDef status, long now) {
        if (!c.active() || "BREACHED".equals(c.state())) {
            return;
        }
        SlaPolicyDef.Pause p = m.pauseOrDefault();
        boolean shouldPause = p.categories().contains(status.category()) || p.statuses().contains(status.code());
        if (shouldPause && !"PAUSED".equals(c.state())) {
            save(t, c, pausedRow(c, now));
        } else if (!shouldPause && "PAUSED".equals(c.state())) {
            save(t, c, resumed(t, c, now));
        }
    }

    private static Row pausedRow(Row c, long now) {
        return new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), "PAUSED", c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), c.pausedMs(), now, c.breachedAtMs(), c.stoppedAtMs(),
                c.epoch() + 1, c.version());
    }

    /** 恢复：暂停那一刻还剩多少，就从现在起重新铺多少。 */
    private Row resumed(TenantContext t, Row c, long now) {
        Optional<BusinessCalendar> cal = calendar(t, c.calendarCode());
        long since = c.pausedSinceMs();
        Instant from = Instant.ofEpochMilli(since);
        Instant nowI = Instant.ofEpochMilli(now);
        long due = shift(cal, from, Instant.ofEpochMilli(c.dueAtMs()), nowI);
        boolean warned = c.warnAtMs() != null && c.warnAtMs() <= since;
        Long warn = c.warnAtMs() == null || warned ? c.warnAtMs() : shift(cal, from, Instant.ofEpochMilli(c.warnAtMs()), nowI);
        Row r = new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), warned ? "WARNING" : "RUNNING", c.startedAtMs(), due, warn, c.pausedMs() + (now - since), null, c.breachedAtMs(),
                c.stoppedAtMs(), c.epoch() + 1, c.version());
        return due <= now ? breachedRow(r, now) : schedule(t, r);
    }

    private Row stop(TenantContext t, Row c, long now, boolean cancelled) {
        long paused = c.pausedMs() + ("PAUSED".equals(c.state()) && c.pausedSinceMs() != null ? now - c.pausedSinceMs() : 0);
        String state = "BREACHED".equals(c.state()) ? "BREACHED" : cancelled ? "CANCELLED" : "MET";
        Row r = new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), state, c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), paused, null, c.breachedAtMs(), now, c.epoch() + 1, c.version());
        return r;
    }

    private void breach(TenantContext t, Row c, long now) {
        save(t, c, breachedRow(c, now));
    }

    private static Row breachedRow(Row c, long now) {
        return new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), "BREACHED", c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), c.pausedMs(), c.pausedSinceMs(), now, c.stoppedAtMs(),
                c.epoch(), c.version());
    }

    private static Row withState(Row c, String state, Long stoppedAt) {
        return new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), state, c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), c.pausedMs(), c.pausedSinceMs(), c.breachedAtMs(),
                stoppedAt == null ? c.stoppedAtMs() : stoppedAt, c.epoch(), c.version());
    }

    private static Row bump(Row c) {
        return new Row(c.accountId(), c.workspaceId(), c.clockId(), c.instanceNo(), c.policyCode(), c.policyVersion(), c.metricCode(), c.targetMs(),
                c.calendarCode(), c.state(), c.startedAtMs(), c.dueAtMs(), c.warnAtMs(), c.pausedMs(), c.pausedSinceMs(), c.breachedAtMs(),
                c.stoppedAtMs(), c.epoch() + 1, c.version());
    }

    private Row schedule(TenantContext t, Row r) {
        timers.schedule(DUE_TASK, new TimerId(t.workspaceId(), r.clockId(), r.epoch()), Instant.ofEpochMilli(r.dueAtMs()));
        if (r.warnAtMs() != null && "RUNNING".equals(r.state())) {
            timers.schedule(WARN_TASK, new TimerId(t.workspaceId(), r.clockId(), r.epoch()), Instant.ofEpochMilli(r.warnAtMs()));
        }
        return r;
    }

    // ── 工具 ────────────────────────────────────────────────

    /** 条件写回；返回写回后的行（版本 + 1）。0 行 = 并发冲突，整个迁移回滚。 */
    private Row save(TenantContext t, Row before, Row after) {
        ConditionalUpdate.requireExactlyOne(clocks.update(t.workspaceId(), after), "sla clock " + before.clockId());
        Row saved = new Row(after.accountId(), after.workspaceId(), after.clockId(), after.instanceNo(), after.policyCode(), after.policyVersion(),
                after.metricCode(), after.targetMs(), after.calendarCode(), after.state(), after.startedAtMs(), after.dueAtMs(), after.warnAtMs(),
                after.pausedMs(), after.pausedSinceMs(), after.breachedAtMs(), after.stoppedAtMs(), after.epoch(), after.version() + 1);
        if (!java.util.Objects.equals(before.state(), saved.state())) {
            logs.insert(t.workspaceId(), new TransitionLogDao.Entry(t.accountId().value(), "SLA", saved.clockId(), saved.instanceNo(), null, null,
                    saved.metricCode(), before.state(), saved.state(), null, "system", null, null,
                    Map.of("policy", saved.policyCode(), "dueAtMs", String.valueOf(saved.dueAtMs())), clock.millis()));
        }
        eventFor(before.state(), saved.state()).ifPresent(kind -> emit(t, saved, kind));
        return saved;
    }

    /**
     * 对外事件只由状态变化决定、只在写回成功后发（同事务写发件箱，回滚则一起消失）：
     * 进入 BREACHED → breached；从计时中进入 WARNING → warning（暂停后恢复回 WARNING 不重发）；进入 MET → met。
     */
    private static Optional<String> eventFor(String before, String after) {
        if ("BREACHED".equals(after) && !"BREACHED".equals(before)) {
            return Optional.of("breached");
        }
        if ("WARNING".equals(after) && ("RUNNING".equals(before) || "PENDING".equals(before))) {
            return Optional.of("warning");
        }
        if ("MET".equals(after) && !"MET".equals(before)) {
            return Optional.of("met");
        }
        return Optional.empty();
    }

    private void emit(TenantContext t, Row r, String kind) {
        events.publish(new SlaChanged(t, kind, r.clockId(), r.instanceNo(), r.policyCode(), r.metricCode(), r.state(), r.startedAtMs(), r.dueAtMs(),
                r.pausedMs(), r.epoch(), clock.instant()));
    }

    private static Optional<SlaPolicyDef.Target> match(SlaPolicyDef def, Map<String, Object> fields) {
        return def.targets().stream().filter(r -> r.when() == null || ConditionEvaluator.evaluate(r.when(), fields)).findFirst();
    }

    private static SlaPolicyDef.Metric metric(SlaPolicyDef def, String code) {
        return def.metrics().stream().filter(m -> m.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("metric " + code + " missing from policy " + def.code()));
    }

    private static boolean hits(SlaPolicyDef.Trigger trig, StatusDef to, String code) {
        return trig.categories().contains(to.category()) || trig.transitions().contains(code);
    }

    private Optional<BusinessCalendar> calendar(TenantContext t, String code) {
        if (code == null) {
            return Optional.empty();
        }
        Optional<BusinessCalendar> c = calendars.find(t, code);
        if (c.isEmpty()) {
            log.warn("SLA 引用的工作日历不存在，按自然时间计 calendar={} workspace={}，需在空间配置该日历", code, t.workspaceId().value());
        }
        return c;
    }

    private static Instant plus(Optional<BusinessCalendar> cal, Instant from, Duration d) {
        return cal.map(c -> c.plus(from, d)).orElseGet(() -> from.plus(d));
    }

    /** 把 {@code point} 相对 {@code from} 的剩余量平移到 {@code to} 之后（有日历时剩余量按营业时间算）。 */
    private static long shift(Optional<BusinessCalendar> cal, Instant from, Instant point, Instant to) {
        if (!point.isAfter(from)) {
            return point.toEpochMilli();
        }
        return cal.map(c -> c.plus(to, c.between(from, point))).orElseGet(() -> to.plus(Duration.between(from, point))).toEpochMilli();
    }
}
