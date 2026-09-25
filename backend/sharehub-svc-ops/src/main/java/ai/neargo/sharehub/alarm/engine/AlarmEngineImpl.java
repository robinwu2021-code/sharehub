package ai.neargo.sharehub.alarm.engine;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmCloseReason;
import ai.neargo.sharehub.alarm.AlarmDisposition;
import ai.neargo.sharehub.alarm.AlarmDomain;
import ai.neargo.sharehub.alarm.AlarmStateMachine;
import ai.neargo.sharehub.alarm.AlarmStatus;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.MergeScope;
import ai.neargo.sharehub.alarm.RecoverRule;
import ai.neargo.sharehub.alarm.dispose.AutoFixer;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.DispositionPreview;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.TickResult;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmCondition;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmSiteProfile;
import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.eval.EvalScope;
import ai.neargo.sharehub.alarm.eval.EventEvaluator;
import ai.neargo.sharehub.alarm.eval.Finding;
import ai.neargo.sharehub.alarm.eval.SafetySignalEvaluator;
import ai.neargo.sharehub.alarm.eval.StateEvaluator;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.AlarmLogMapper;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.ConditionMapper;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.RouteMapper;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.SiteProfileMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmMapper;
import ai.neargo.sharehub.alarm.todo.AlarmTodoService;
import ai.neargo.sharehub.api.core.port.DeviceProtectionPort;
import ai.neargo.sharehub.api.core.port.TradeAnomalyPort;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.api.platform.port.SiteStatePort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.dto.CsDtos.TicketCreateReq;
import ai.neargo.sharehub.cs.service.CsTicketService;
import ai.neargo.sharehub.wo.WoPriority;
import ai.neargo.sharehub.wo.WorkOrderStatus;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务告警引擎实现（TDD/05 §五–§七）。
 *
 * <p><b>事务边界</b>：判定纯读；每批站点的对账一个事务（开 / 累加 / 恢复）；每条告警的处置一个事务
 * —— 开单失败不回滚已成立的告警，下一轮 {@code dispatchDue} 重试。
 * <b>系统身份</b>：整轮豁免数据范围（这是服务端判定，不是某个用户在查数据）。
 */
@Service
public class AlarmEngineImpl implements AlarmEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmEngineImpl.class);
    static final String SYSTEM = "SYSTEM";
    static final String EVAL = "EVAL";
    private static final int BATCH = 200;
    private static final int MAX_FIX_TRIES = 3;
    /** tick 停摆后恢复：单次最多计 5 分钟，不把停摆期整段算进持续时长。 */
    private static final int MAX_STEP_MIN = 5;
    private static final List<String> OPEN = List.of(AlarmStatus.OPEN.name(), AlarmStatus.ACKED.name());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DevAlarmMapper alarms;
    private final DevAlarmCodeMapper codeMapper;
    private final RouteMapper routes;
    private final ConditionMapper conditions;
    private final AlarmLogMapper logs;
    private final SiteProfileMapper profiles;
    private final AlarmStateMachine sm;
    private final List<StateEvaluator> stateEvaluators;
    private final List<EventEvaluator<?>> eventEvaluators;
    private final Map<String, AutoFixer> fixers;
    private final ImpactAssessor impact;
    private final SiteStatePort siteState;
    private final SiteQueryPort siteQuery;
    private final DeviceProtectionPort protection;
    private final TradeAnomalyPort trade;
    private final WoOpsService woOps;
    private final CsTicketService csTickets;
    private final AlarmTodoService todos;
    private final ai.neargo.sharehub.alarm.dispose.AlarmNotifier notifier;
    /** 判定 / 处置 / 恢复：有外层事务就加入（测试随之回滚），任务运行时各自新开。 */
    private final TransactionTemplate tx;
    /**
     * 强制新事务，只用于两处：①事件路径 —— 事件在上游事务 afterCommit 里投递，那时加入等于写进已提交的旧连接；
     * ②自愈动作 —— 它失败会把事务标成 rollback-only，不隔离的话「第几次尝试」的记录跟着回滚，重试计数永远是 0。
     */
    private final TransactionTemplate txNew;

    public AlarmEngineImpl(DevAlarmMapper alarms, DevAlarmCodeMapper codeMapper, RouteMapper routes, ConditionMapper conditions,
                           AlarmLogMapper logs, SiteProfileMapper profiles, AlarmStateMachine sm,
                           List<StateEvaluator> stateEvaluators, List<EventEvaluator<?>> eventEvaluators, List<AutoFixer> fixers,
                           ImpactAssessor impact, SiteStatePort siteState, SiteQueryPort siteQuery, DeviceProtectionPort protection,
                           TradeAnomalyPort trade, WoOpsService woOps, CsTicketService csTickets, AlarmTodoService todos,
                           ai.neargo.sharehub.alarm.dispose.AlarmNotifier notifier, PlatformTransactionManager tm) {
        this.notifier = notifier;
        this.alarms = alarms;
        this.codeMapper = codeMapper;
        this.routes = routes;
        this.conditions = conditions;
        this.logs = logs;
        this.profiles = profiles;
        this.sm = sm;
        this.stateEvaluators = stateEvaluators;
        this.eventEvaluators = eventEvaluators;
        this.fixers = fixers.stream().collect(Collectors.toMap(AutoFixer::code, Function.identity()));
        this.impact = impact;
        this.siteState = siteState;
        this.siteQuery = siteQuery;
        this.protection = protection;
        this.trade = trade;
        this.woOps = woOps;
        this.csTickets = csTickets;
        this.todos = todos;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.txNew = new TransactionTemplate(tm);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ═══════════════════════════ 每分钟 ═══════════════════════════

    @Override
    public TickResult tick(LocalDateTime now) {
        return run(now, null);
    }

    @Override
    public TickResult tickSites(LocalDateTime now, Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return new TickResult(0, 0, 0, 0);
        return run(now, new HashSet<>(siteNos));
    }

    /** @param only null = 全部站点；否则只判这些站点（其余站点的告警一概不碰） */
    private TickResult run(LocalDateTime now, Set<String> only) {
        return DataScopeContext.executeWithoutScope(() -> {
            Map<String, DevAlarmCode> codes = codes();
            int[] c = new int[4];
            Set<String> seen = new HashSet<>();
            long afterId = 0;
            while (true) {
                List<SiteBrief> batch;
                if (only == null) {
                    batch = siteState.listByStatus(List.of("ACTIVE", "PAUSED"), afterId, BATCH);
                } else {
                    batch = afterId > 0 ? List.of() : siteQuery.briefsByNos(only).stream()
                            .filter(s -> "ACTIVE".equals(s.status()) || "PAUSED".equals(s.status())).toList();
                }
                if (batch.isEmpty()) break;
                afterId = only != null ? 1 : siteState.idOf(batch.get(batch.size() - 1).siteNo());
                EvalScope scope = new EvalScope(batch);
                Set<String> siteNos = batch.stream().map(SiteBrief::siteNo).collect(Collectors.toSet());
                seen.addAll(siteNos);
                Map<String, SiteBrief> siteMap = batch.stream().collect(Collectors.toMap(SiteBrief::siteNo, s -> s, (a, b) -> a));
                for (StateEvaluator ev : stateEvaluators) {
                    if (!ev.siteBatched()) continue;
                    runEvaluator(ev, scope, siteNos, siteMap, codes, now, c, null);
                }
                if (only != null || batch.size() < BATCH) break;
            }
            for (StateEvaluator ev : stateEvaluators) {
                if (ev.siteBatched()) continue;
                runEvaluator(ev, new EvalScope(List.of()), only, Map.of(), codes, now, c, only);
            }
            c[1] += recoverOrphans(seen, codes, now, only);
            c[3] += dispatchDueInternal(now, only);
            c[2] += closeRecovered(codes, now, only);
            escalateStale(codes, now, only);
            backfillSafetyLocks(codes, now, only);
            TickResult r = new TickResult(c[0], c[1], c[2], c[3]);
            if (c[0] + c[1] + c[2] + c[3] > 0) log.info("告警判定 opened={} recovered={} closed={} disposed={}", c[0], c[1], c[2], c[3]);
            return r;
        });
    }

    private void runEvaluator(StateEvaluator ev, EvalScope scope, Set<String> siteNos, Map<String, SiteBrief> siteMap,
                              Map<String, DevAlarmCode> codes, LocalDateTime now, int[] c, Set<String> only) {
        List<Finding> findings;
        try {
            List<Finding> all = ev.evaluate(scope, now);
            findings = only == null ? all : all.stream().filter(f -> f.siteNo() != null && only.contains(f.siteNo())).toList();
        } catch (RuntimeException e) {
            // 一个判定器坏了不拖垮其它判定器；这一批它负责的告警本轮既不开也不恢复
            log.error("告警判定器失败 evaluator={} codes={}，本轮跳过", ev.getClass().getSimpleName(), ev.codes(), e);
            return;
        }
        List<String> dueNow = tx.execute(st -> reconcile(ev.codes(), siteNos, siteMap, findings, codes, now, c));
        if (dueNow != null) dueNow.forEach(no -> disposeSafely(no, now));
    }

    /** 对账一批：成立的开或累加；不再成立的进入恢复。返回需立即处置的告警号。 */
    private List<String> reconcile(Set<String> evalCodes, Set<String> siteNos, Map<String, SiteBrief> siteMap,
                                   List<Finding> findings, Map<String, DevAlarmCode> codes, LocalDateTime now, int[] c) {
        List<String> dueNow = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Finding f : findings) {
            DevAlarmCode code = codes.get(f.code());
            if (code == null || !on(code.getEnabled())) continue;
            keys.add(f.dedupKey());
            DevAlarm open = openOf(f.dedupKey());
            if (open != null) {
                if (open.getRecoveredAt() != null) {
                    alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, open.getId()).set(DevAlarm::getRecoveredAt, null));
                    open.setRecoveredAt(null);
                    writeLog(open.getAlarmNo(), "RELAPSE", "防抖期内复发", SYSTEM);
                }
                bump(open, f, code, siteMap.get(f.siteNo()), now, false);
                continue;
            }
            DevAlarmCondition cond = conditions.selectOne(new LambdaQueryWrapper<DevAlarmCondition>()
                    .eq(DevAlarmCondition::getDedupKey, f.dedupKey()).last("limit 1"));
            if (cond == null) {
                cond = new DevAlarmCondition();
                cond.setDedupKey(f.dedupKey());
                cond.setAlarmCode(f.code());
                cond.setSubjectSite(f.siteNo());
                cond.setFirstSeenAt(now);
                cond.setLastSeenAt(now);
                cond.setHeldMinutes(0);
                conditions.insert(cond);
            } else if (now.isAfter(cond.getLastSeenAt())) {
                LocalDateTime from = cond.getLastSeenAt().isBefore(now.minusMinutes(MAX_STEP_MIN)) ? now.minusMinutes(MAX_STEP_MIN) : cond.getLastSeenAt();
                SiteBrief site = siteMap.get(f.siteNo());
                int add = on(code.getBusinessHoursOnly())
                        ? BusinessHours.openMinutes(site == null ? null : site.openHours(), from, now)
                        : (int) Duration.between(from, now).toMinutes();
                cond.setHeldMinutes(cond.getHeldMinutes() + add);
                cond.setLastSeenAt(now);
                conditions.updateById(cond);
            }
            int hold = code.getHoldMinutes() == null ? 0 : code.getHoldMinutes();
            if (cond.getHeldMinutes() >= hold) {
                DevAlarm a = open(f, code, siteMap.get(f.siteNo()), now, cond.getFirstSeenAt());
                conditions.deleteById(cond.getId());
                if (a != null) {
                    c[0]++;
                    if (a.getDueAt() != null && !a.getDueAt().isAfter(now)) dueNow.add(a.getAlarmNo());
                }
            }
        }
        // 不再成立的：进入恢复（防抖期后由 closeRecovered 关闭）
        LambdaQueryWrapper<DevAlarm> w = new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .in(DevAlarm::getStatus, OPEN).in(DevAlarm::getAlarmCode, evalCodes);
        if (siteNos != null) {
            if (siteNos.isEmpty()) return dueNow;
            w.in(DevAlarm::getSiteNo, siteNos);
        }
        for (DevAlarm a : alarms.selectList(w)) {
            if (keys.contains(a.getDedupKey()) || a.getRecoveredAt() != null) continue;
            DevAlarmCode code = codes.get(a.getAlarmCode());
            if (code == null || !RecoverRule.SIGNAL_CLEAR.name().equals(code.getRecoverRule())) continue;
            alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId()).set(DevAlarm::getRecoveredAt, now));
            writeLog(a.getAlarmNo(), "RECOVER", "条件已消失，防抖 " + code.getRecoverHoldMinutes() + " 分钟后关闭", SYSTEM);
            c[1]++;
        }
        // 计时中断即清零
        LambdaQueryWrapper<DevAlarmCondition> cw = new LambdaQueryWrapper<DevAlarmCondition>().in(DevAlarmCondition::getAlarmCode, evalCodes);
        if (siteNos != null) cw.in(DevAlarmCondition::getSubjectSite, siteNos);
        for (DevAlarmCondition cond : conditions.selectList(cw)) {
            if (!keys.contains(cond.getDedupKey())) conditions.deleteById(cond.getId());
        }
        return dueNow;
    }

    /**
     * 站点已不在营业 / 暂停（撤场、关闭、归档）：它不会再进入任何批次，其上按站点判定的告警永远等不到「条件消失」。
     * 这里统一置恢复，走正常的防抖关闭。
     */
    private int recoverOrphans(Set<String> seen, Map<String, DevAlarmCode> codes, LocalDateTime now, Set<String> only) {
        Set<String> batchedCodes = stateEvaluators.stream().filter(StateEvaluator::siteBatched)
                .flatMap(e -> e.codes().stream()).collect(Collectors.toSet());
        if (batchedCodes.isEmpty()) return 0;
        int n = 0;
        for (DevAlarm a : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .in(DevAlarm::getStatus, OPEN).in(DevAlarm::getAlarmCode, batchedCodes).isNull(DevAlarm::getRecoveredAt)
                .isNotNull(DevAlarm::getSiteNo).in(only != null, DevAlarm::getSiteNo, only))) {
            if (seen.contains(a.getSiteNo())) continue;
            DevAlarmCode code = codes.get(a.getAlarmCode());
            if (code == null || !RecoverRule.SIGNAL_CLEAR.name().equals(code.getRecoverRule())) continue;
            tx.executeWithoutResult(st -> {
                alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId()).set(DevAlarm::getRecoveredAt, now));
                writeLog(a.getAlarmNo(), "RECOVER", "站点已不在营业范围", SYSTEM);
            });
            n++;
        }
        return n;
    }

    // ═══════════════════════════ 事件 ═══════════════════════════

    @Override
    public void onEvent(Object event, LocalDateTime now) {
        DataScopeContext.executeWithoutScope(() -> {
            Map<String, DevAlarmCode> codes = codes();
            // 独立事务：事件多在上游事务的 afterCommit 里投递，那时不开新事务的写入会落在已提交的旧连接上、永不提交
            List<String> dueNow = txNew.execute(st -> judgeEvent(event, codes, now));
            if (dueNow != null) dueNow.forEach(no -> disposeSafely(no, now, txNew));
            return null;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> judgeEvent(Object event, Map<String, DevAlarmCode> codes, LocalDateTime now) {
        List<String> dueNow = new ArrayList<>();
        for (EventEvaluator ev : eventEvaluators) {
            if (!ev.eventType().isInstance(event)) continue;
            for (Object o : ev.onEvent(event)) {
                Finding f = (Finding) o;
                DevAlarmCode code = codes.get(f.code());
                if (code == null || !on(code.getEnabled())) continue;
                SiteBrief site = site(f.siteNo());
                DevAlarm open = openOf(f.dedupKey());
                if (open != null) {
                    bump(open, f, code, site, now, true);
                    continue;
                }
                DevAlarm a = open(f, code, site, now, now);
                if (a != null && a.getDueAt() != null && !a.getDueAt().isAfter(now)) dueNow.add(a.getAlarmNo());
            }
        }
        return dueNow;
    }

    // ═══════════════════════════ 开 / 累加 / 取代 ═══════════════════════════

    private DevAlarm open(Finding f, DevAlarmCode code, SiteBrief site, LocalDateTime now, LocalDateTime firstSeen) {
        ImpactAssessor.Impact im = impact.assess(f, code, site == null ? null : site.openHours(), profileOf(f.siteNo()), now);
        firstSeen = carriedFirstSeen(f.dedupKey(), firstSeen, now);
        DevAlarm a = new DevAlarm();
        a.setAlarmNo(IdGenerator.next(BizKey.ALARM));
        a.setTenantId("MAIN");
        a.setAlarmCode(f.code());
        a.setLevel(code.getLevel());
        a.setSource(EVAL);
        a.setDomain(code.getDomain());
        a.setSubjectType(f.subjectType().name());
        a.setSubjectNo(f.subjectNo());
        a.setSiteNo(f.siteNo());
        a.setCabinetNo(f.cabinetNo());
        a.setAgentNo(f.agentNo());
        a.setRegionId(f.regionId() != null ? f.regionId() : site == null ? null : site.regionId());
        a.setCause(f.cause() == null ? null : f.cause().name());
        a.setImpactScope(im.scope() == null ? null : im.scope().name());
        a.setImpactPeriod(im.period().name());
        a.setSiteTier(im.tier());
        a.setInFlightOrders(im.inFlight());
        a.setPriority(im.priority().name());
        a.setEvidence(evidenceJson(f.evidence()));
        a.setStatus(AlarmStatus.OPEN.name());
        a.setDedupKey(f.dedupKey());
        a.setCount(1);
        a.setOccurredAt(firstSeen.format(TS));
        a.setFirstOccurredAt(firstSeen);
        a.setLastOccurredAt(now);
        a.setDueAt(now.plusMinutes(code.getWoDelayMinutes() == null ? 0 : code.getWoDelayMinutes()));
        try {
            alarms.insert(a);
        } catch (DuplicateKeyException race) {
            // 并发：别的线程刚开了同一条（uk_alarm_open）→ 按累加处理
            DevAlarm existing = openOf(f.dedupKey());
            if (existing != null) bump(existing, f, code, site, now, true);
            return null;
        }
        writeLog(a.getAlarmNo(), "OPEN", "成立：" + f.cause() + " · 优先级 " + im.priority() + " · 影响 " + im.scope() + "/" + im.period(), SYSTEM);
        log.info("业务告警成立 alarmNo={} code={} subject={}:{} cause={} priority={}", a.getAlarmNo(), a.getAlarmCode(),
                a.getSubjectType(), a.getSubjectNo(), a.getCause(), a.getPriority());
        holdOfflineStop(a, f);
        supersede(a, code);
        notifySafely(a, "OPENED", code, now);
        return a;
    }

    /**
     * 层级取代后复发（TDD/05 §5.5）：柜级告警曾被站点级取代，站点级恢复后该柜仍有问题 → 新开的柜级告警
     * 沿用被取代那条的起始时刻，持续时长不重置。只认「上层告警刚关（1 小时内）」的那条，旧历史不接续。
     */
    private LocalDateTime carriedFirstSeen(String dedupKey, LocalDateTime firstSeen, LocalDateTime now) {
        DevAlarm prev = alarms.selectOne(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .eq(DevAlarm::getDedupKey, dedupKey).eq(DevAlarm::getCloseReason, AlarmCloseReason.SUPERSEDED.name())
                .orderByDesc(DevAlarm::getId).last("limit 1"));
        if (prev == null || prev.getParentAlarmNo() == null || prev.getFirstOccurredAt() == null) return firstSeen;
        DevAlarm upper = byNo(prev.getParentAlarmNo());
        if (upper == null || !AlarmStatus.CLOSED.name().equals(upper.getStatus()) || upper.getClosedAt() == null) return firstSeen;
        if (upper.getClosedAt().isBefore(now.minusHours(1))) return firstSeen;
        return prev.getFirstOccurredAt().isBefore(firstSeen) ? prev.getFirstOccurredAt() : firstSeen;
    }

    /** 离线 → 以告警身份整柜停借（设备不会主动报离线，网关的 online 快照可能还是 ONLINE）。告警关闭时随之释放。 */
    private void holdOfflineStop(DevAlarm a, Finding f) {
        boolean declared = f.attrs() != null && "true".equals(f.attrs().get(Finding.ATTR_STOP_RENT));
        if (f.cause() != AlarmCause.OFFLINE && !declared) return;
        Set<String> cabs = new HashSet<>();
        if (f.cabinetNo() != null) cabs.add(f.cabinetNo());
        for (Finding.Evidence e : f.evidence()) if ("OFFLINE".equals(e.signal()) && e.cabinetNo() != null) cabs.add(e.cabinetNo());
        for (String cab : cabs) {
            try {
                protection.apply(cab, null, "STOP_RENT", a.getAlarmNo(),
                        (declared ? a.getAlarmCode() : "离线") + "（告警 " + a.getAlarmNo() + "）");
            } catch (RuntimeException e) {
                log.warn("离线停借申请失败 alarmNo={} cabinetNo={}，告警照常成立", a.getAlarmNo(), cab, e);
            }
        }
    }

    private void bump(DevAlarm a, Finding f, DevAlarmCode code, SiteBrief site, LocalDateTime now, boolean countUp) {
        ImpactAssessor.Impact im = impact.assess(f, code, site == null ? null : site.openHours(), profileOf(f.siteNo()), now);
        LambdaUpdateWrapper<DevAlarm> u = new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId())
                .set(DevAlarm::getLastOccurredAt, now).set(DevAlarm::getEvidence, evidenceJson(f.evidence()));
        if (countUp) u.setSql("count = COALESCE(count, 0) + 1");
        // 影响只升不降
        boolean up = a.getPriority() == null || im.priority().higherThan(WoPriority.of(a.getPriority()));
        if (up) {
            u.set(DevAlarm::getPriority, im.priority().name()).set(DevAlarm::getImpactPeriod, im.period().name())
                    .set(DevAlarm::getInFlightOrders, im.inFlight()).set(DevAlarm::getSiteTier, im.tier());
        }
        alarms.update(null, u);
        if (up && a.getPriority() != null) {
            writeLog(a.getAlarmNo(), "IMPACT_UP", a.getPriority() + " → " + im.priority() + "（" + im.period() + "）", SYSTEM);
            a.setPriority(im.priority().name());
            notifySafely(a, "IMPACT_UP", code, now);
            if (AlarmDisposition.WORK_ORDER.name().equals(a.getDispositionType()) && a.getDispositionRef() != null) {
                woOps.raisePriority(a.getDispositionRef(), im.priority().name(), "告警 " + a.getAlarmNo() + " 影响升级");
            }
        }
    }

    /** 层级取代：站点级成立时，同站点未关闭的柜级告警并入（关闭为 SUPERSEDED，工单并单）。 */
    private void supersede(DevAlarm upper, DevAlarmCode code) {
        if (code.getSupersedes() == null || upper.getSiteNo() == null) return;
        for (DevAlarm lower : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .eq(DevAlarm::getAlarmCode, code.getSupersedes()).eq(DevAlarm::getSiteNo, upper.getSiteNo()).in(DevAlarm::getStatus, OPEN))) {
            alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, lower.getId())
                    .set(DevAlarm::getParentAlarmNo, upper.getAlarmNo()));
            lower.setParentAlarmNo(upper.getAlarmNo());
            writeLog(lower.getAlarmNo(), "SUPERSEDE", "并入 " + upper.getAlarmNo(), SYSTEM);
            close(lower, AlarmCloseReason.SUPERSEDED, "并入 " + upper.getAlarmNo(), SYSTEM);
        }
    }

    /** 通知失败不影响告警本身（通知是附带的，告警已成立）。 */
    private void notifySafely(DevAlarm a, String event, DevAlarmCode code, LocalDateTime now) {
        try {
            notifier.notify(a, event, code == null ? null : code.getMessage(), now);
        } catch (RuntimeException e) {
            log.warn("告警通知失败 alarmNo={} event={}，告警照常成立", a.getAlarmNo(), event, e);
        }
    }

    /** 超时未确认的升级：规则配了 escalate_minutes，告警成立超过该时长仍停在 OPEN（没人确认、也没被处置单接走）。 */
    private void escalateStale(Map<String, DevAlarmCode> codes, LocalDateTime now, Set<String> only) {
        for (DevAlarm a : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .eq(DevAlarm::getStatus, AlarmStatus.OPEN.name()).isNull(DevAlarm::getRecoveredAt)
                .in(only != null, DevAlarm::getSiteNo, only).last("limit 500"))) {
            Integer after = notifier.escalateAfterMinutes(a.getAlarmCode());
            LocalDateTime since = a.getFirstOccurredAt() == null ? a.getCreatedAt() : a.getFirstOccurredAt();
            if (after == null || since == null || since.plusMinutes(after).isAfter(now)) continue;
            tx.executeWithoutResult(st -> notifySafely(a, "ESCALATED", codes.get(a.getAlarmCode()), now));
        }
    }

    // ═══════════════════════════ 恢复 / 关闭 ═══════════════════════════

    private int closeRecovered(Map<String, DevAlarmCode> codes, LocalDateTime now, Set<String> only) {
        int n = 0;
        for (DevAlarm a : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL)
                .in(DevAlarm::getStatus, OPEN).isNotNull(DevAlarm::getRecoveredAt).in(only != null, DevAlarm::getSiteNo, only))) {
            DevAlarmCode code = codes.get(a.getAlarmCode());
            int hold = code == null || code.getRecoverHoldMinutes() == null ? 10 : code.getRecoverHoldMinutes();
            if (a.getRecoveredAt().plusMinutes(hold).isAfter(now)) continue;
            Boolean closed = tx.execute(st -> close(a, AlarmCloseReason.SELF_HEALED, "业务已恢复", SYSTEM));
            if (Boolean.TRUE.equals(closed)) n++;
        }
        return n;
    }

    @Override
    public boolean close(DevAlarm a, AlarmCloseReason reason, String note, String operator) {
        String from = a.getStatus();
        String to = sm.next(from, "CLOSE");
        int n = alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId()).eq(DevAlarm::getStatus, from)
                .set(DevAlarm::getStatus, to).set(DevAlarm::getCloseReason, reason.name()).set(DevAlarm::getCloseNote, note)
                .set(DevAlarm::getClosedBy, operator).set(DevAlarm::getClosedAt, LocalDateTime.now()));
        if (n == 0) return false;
        a.setStatus(to);
        protection.release(a.getAlarmNo(), "告警关闭：" + reason);
        if (reason == AlarmCloseReason.RESOLVED && SafetySignalEvaluator.BATTERY_HAZARD.equals(a.getAlarmCode()) && a.getCabinetNo() != null) {
            // 电池隐患经现场处置并验收：解除信号持有的锁仓（该信号没有「恢复」信号，只能由处置完成解除）
            protection.releaseSignal(a.getCabinetNo(), slotOf(a.getEvidence()), "BATTERY_ABNORMAL", "告警 " + a.getAlarmNo() + " 现场处置完成");
        }
        linkDisposition(a, reason);
        writeLog(a.getAlarmNo(), "CLOSE", reason + (note == null ? "" : "：" + note), operator);
        log.info("业务告警关闭 alarmNo={} code={} reason={}", a.getAlarmNo(), a.getAlarmCode(), reason);
        return true;
    }

    /** 关闭联动处置单（TDD/05 §7.3）。 */
    private void linkDisposition(DevAlarm a, AlarmCloseReason reason) {
        String ref = a.getDispositionRef();
        if (ref == null) return;
        if (AlarmDisposition.TODO.name().equals(a.getDispositionType())) {
            if (reason != AlarmCloseReason.RESOLVED) todos.cancel(ref, "关联告警已关闭：" + reason);
            return;
        }
        if (!AlarmDisposition.WORK_ORDER.name().equals(a.getDispositionType())) return;
        String status = woOps.statusOf(ref);
        if (status == null || WorkOrderStatus.CLOSED.name().equals(status)) return;
        if (reason == AlarmCloseReason.SUPERSEDED && a.getParentAlarmNo() != null) {
            DevAlarm upper = byNo(a.getParentAlarmNo());
            if (upper == null) return;
            if (AlarmDisposition.WORK_ORDER.name().equals(upper.getDispositionType()) && upper.getDispositionRef() != null) {
                woOps.mergeInto(ref, upper.getDispositionRef(), "告警 " + a.getAlarmNo() + " 并入 " + upper.getAlarmNo());
            } else {
                // 上层还没有工单：把下层工单直接改挂为上层告警的处置
                alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, upper.getId())
                        .set(DevAlarm::getDispositionType, AlarmDisposition.WORK_ORDER.name()).set(DevAlarm::getDispositionRef, ref)
                        .set(DevAlarm::getWoNo, ref));
                woOps.annotate(ref, "改挂到上层告警 " + upper.getAlarmNo());
            }
            return;
        }
        if (reason == AlarmCloseReason.SELF_HEALED || reason == AlarmCloseReason.AUTO_FIXED) {
            boolean othersOpen = alarms.selectCount(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getDispositionRef, ref)
                    .in(DevAlarm::getStatus, OPEN).ne(DevAlarm::getId, a.getId())) > 0;
            if (othersOpen) return;
            if (WorkOrderStatus.CREATED.name().equals(status) || WorkOrderStatus.DISPATCHED.name().equals(status)) {
                woOps.withdraw(ref, "关联告警已自动恢复");
            } else {
                woOps.markAlarmRecovered(ref, LocalDateTime.now());
            }
        }
        // FALSE_ALARM：不自动关单（前端提示是否同时以误报关单）
    }

    @Override
    public void closeManually(String alarmNo, String reason, String note) {
        AlarmCloseReason r = AlarmCloseReason.of(reason);
        if (!r.manual()) throw ai.neargo.sharehub.common.BizException.badRequest("error.alarm.system_reason_only", r.name());
        DevAlarm a = byNoScoped(alarmNo);   // 带范围读：看不到的告警不能关
        if (r == AlarmCloseReason.RESOLVED && EVAL.equals(a.getSource())) {
            DevAlarmCode code = codes().get(a.getAlarmCode());
            boolean dispositionOnly = code != null && (AlarmDomain.SAFETY.name().equals(code.getDomain())
                    || RecoverRule.DISPOSITION_DONE.name().equals(code.getRecoverRule()));
            if (dispositionOnly) throw ai.neargo.sharehub.common.BizException.conflict("error.alarm.disposition_only");
        }
        if ((r == AlarmCloseReason.RESOLVED || r == AlarmCloseReason.FALSE_ALARM) && (note == null || note.isBlank())) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.common.note_required");
        }
        String me = SecurityUtils.currentUser().map(LoginUser::userNo).orElse(SYSTEM);
        sm.next(a.getStatus(), "CLOSE");   // 非法迁移 400
        if (!DataScopeContext.executeWithoutScope(() -> close(a, r, note == null ? null : note.trim(), me))) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.common.state_changed");
        }
    }

    // ═══════════════════════════ 处置 ═══════════════════════════

    @Override
    public int dispatchDue(LocalDateTime now) {
        return DataScopeContext.executeWithoutScope(() -> dispatchDueInternal(now, null));
    }

    private int dispatchDueInternal(LocalDateTime now, Set<String> only) {
        int n = 0;
        for (DevAlarm a : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL).in(only != null, DevAlarm::getSiteNo, only)
                .in(DevAlarm::getStatus, OPEN).le(DevAlarm::getDueAt, now).isNull(DevAlarm::getDispositionRef)
                .isNull(DevAlarm::getRecoveredAt).isNull(DevAlarm::getParentAlarmNo).orderByAsc(DevAlarm::getId).last("limit 500"))) {
            if (AlarmDisposition.NOTIFY.name().equals(a.getDispositionType())) continue;   // 仅通知：已处置过
            if (disposeSafely(a.getAlarmNo(), now)) n++;
        }
        return n;
    }

    private boolean disposeSafely(String alarmNo, LocalDateTime now) {
        return disposeSafely(alarmNo, now, tx);
    }

    private boolean disposeSafely(String alarmNo, LocalDateTime now, TransactionTemplate template) {
        try {
            return Boolean.TRUE.equals(template.execute(st -> {
                DevAlarm a = byNo(alarmNo);
                return a != null && OPEN.contains(a.getStatus()) && dispose(a, now, null) != null;
            }));
        } catch (RuntimeException e) {
            // 处置失败不回滚告警本身；下一轮 dispatchDue 重试。需要人看的是持续失败的那一条
            log.error("告警处置失败 alarmNo={}，下一轮重试", alarmNo, e);
            return false;
        }
    }

    /** @return 处置结果标记（单号 / "AUTO_FIX" / "NOTIFY"）；null = 本次未处置成功 */
    private String dispose(DevAlarm a, LocalDateTime now, String forced) {
        DevAlarmCode code = codes().get(a.getAlarmCode());
        if (code == null) return null;
        DevAlarmRoute route = routeOf(a.getAlarmCode(), a.getCause());
        String type = forced != null ? forced : route != null ? route.getDisposition() : code.getDisposition();
        switch (AlarmDisposition.of(type)) {
            case AUTO_FIX -> {
                AutoFixer fixer = fixers.get(a.getAlarmCode());
                long tries = logs.selectCount(new LambdaQueryWrapper<DevAlarmLog>().eq(DevAlarmLog::getAlarmNo, a.getAlarmNo())
                        .eq(DevAlarmLog::getEvent, "FIX_TRY"));
                if (fixer == null || tries >= MAX_FIX_TRIES) {
                    String fb = route != null && route.getFallback() != null ? route.getFallback() : AlarmDisposition.WORK_ORDER.name();
                    return dispose(a, now, fb);
                }
                writeLog(a.getAlarmNo(), "FIX_TRY", "第 " + (tries + 1) + " 次自愈", SYSTEM);
                boolean ok;
                try {
                    // 自愈动作独立事务：它失败（含把事务标成 rollback-only）不能连带回滚「第几次尝试」的记录，
                    // 否则重试计数永远是 0，兜底处置永远不触发
                    ok = Boolean.TRUE.equals(txNew.execute(st -> fixer.fix(a)));
                } catch (RuntimeException e) {
                    log.warn("自愈动作异常 alarmNo={} code={}，计一次失败", a.getAlarmNo(), a.getAlarmCode(), e);
                    ok = false;
                }
                if (ok) {
                    close(a, AlarmCloseReason.AUTO_FIXED, "系统自愈成功", SYSTEM);
                    return "AUTO_FIX";
                }
                writeLog(a.getAlarmNo(), "FIX_FAIL", "第 " + (tries + 1) + " 次自愈失败", SYSTEM);
                if (tries + 1 >= MAX_FIX_TRIES) {
                    String fb = route != null && route.getFallback() != null ? route.getFallback() : AlarmDisposition.WORK_ORDER.name();
                    return dispose(a, now, fb);
                }
                return null;
            }
            case WORK_ORDER -> {
                String woType = route != null && route.getWoType() != null ? route.getWoType() : "FAULT";
                int delta = route != null && route.getPriorityDelta() != null ? route.getPriorityDelta() : 0;
                WoPriority p = WoPriority.of(a.getPriority() == null ? code.getBasePriority() : a.getPriority()).plus(delta);
                String woNo = woOps.openOrAttach(new AlarmDraft(woType, p.name(), mergeKey(a, code, woType, now),
                        a.getSubjectType() != null && AlarmSubjectType.SITE.name().equals(a.getSubjectType()) ? null : a.getCabinetNo(),
                        a.getSiteNo(), description(a, code), a.getAlarmNo()));
                markDisposed(a, AlarmDisposition.WORK_ORDER, woNo, "工单 " + woNo);
                return woNo;
            }
            case CS_CASE -> {
                TradeAnomalyPort.OrderAnomaly o = AlarmSubjectType.ORDER.name().equals(a.getSubjectType()) ? trade.orderInfo(a.getSubjectNo()) : null;
                if (o == null || o.cUserNo() == null) return dispose(a, now, AlarmDisposition.WORK_ORDER.name());   // 没有用户可跟进
                String ticketNo = csTickets.create(new TicketCreateReq(null, o.cUserNo(), o.orderNo(), a.getCabinetNo(), null,
                        description(a, code), "ALARM", null)).ticketNo();
                markDisposed(a, AlarmDisposition.CS_CASE, ticketNo, "客服单 " + ticketNo);
                return ticketNo;
            }
            case TODO -> {
                String todoNo = todos.create(a, code.getOwnerRole()).todoNo();
                markDisposed(a, AlarmDisposition.TODO, todoNo, "待办 " + todoNo);
                return todoNo;
            }
            case NOTIFY -> {
                alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId())
                        .set(DevAlarm::getDispositionType, AlarmDisposition.NOTIFY.name()));
                writeLog(a.getAlarmNo(), "DISPOSE", "仅通知", SYSTEM);
                return "NOTIFY";
            }
            default -> {
                return null;
            }
        }
    }

    private void markDisposed(DevAlarm a, AlarmDisposition type, String ref, String note) {
        LambdaUpdateWrapper<DevAlarm> u = new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId())
                .set(DevAlarm::getDispositionType, type.name()).set(DevAlarm::getDispositionRef, ref);
        if (type == AlarmDisposition.WORK_ORDER) u.set(DevAlarm::getWoNo, ref);   // 兼容旧列：列表「关联工单」读它
        alarms.update(null, u);
        a.setDispositionType(type.name());
        a.setDispositionRef(ref);
        writeLog(a.getAlarmNo(), "DISPOSE", note, SYSTEM);
    }

    /**
     * 合并键。<b>补宝 / 取宝（REFILL）一律按「区域 × 当天」</b>（对齐清单 D3）：它们是一条调度路线上的若干站，
     * 一张调度单挂多个站点的告警（= 多站点明细）；也因此「借不到（无宝）」与「可借不足」落到同一张单，不重复派人。
     * 没有区域的站点退回按站点合并，免得所有无区域站点被捏成一张单。
     */
    private static String mergeKey(DevAlarm a, DevAlarmCode code, String woType, LocalDateTime now) {
        if (DISPATCH_WO_TYPE.equals(woType)) {
            String day = now.toLocalDate().toString().replace("-", "");
            return a.getRegionId() != null ? "ALM:" + a.getRegionId() + ":" + woType + ":" + day
                    : "ALM:" + a.getSiteNo() + ":" + woType + ":" + day;
        }
        MergeScope scope = code.getMergeScope() == null ? MergeScope.DEVICE : MergeScope.of(code.getMergeScope());
        return switch (scope) {
            case SITE -> "ALM:" + a.getSiteNo() + ":" + woType;
            case REGION -> "ALM:" + a.getRegionId() + ":" + woType + ":" + now.toLocalDate().toString().replace("-", "");
            default -> "ALM:" + (a.getCabinetNo() != null ? a.getCabinetNo() : a.getSubjectNo()) + ":" + woType;
        };
    }

    /** 调度类工单（补宝 / 取宝 / 换宝）。 */
    private static final String DISPATCH_WO_TYPE = ai.neargo.sharehub.wo.ext.WorkOrderType.REFILL.name();

    private static String description(DevAlarm a, DevAlarmCode code) {
        StringBuilder sb = new StringBuilder(code.getMessage() == null ? a.getAlarmCode() : code.getMessage());
        sb.append("｜影响：").append(a.getImpactScope()).append('/').append(a.getImpactPeriod());
        if (a.getCause() != null) sb.append("｜根因：").append(a.getCause());
        if (code.getSuggestion() != null) sb.append("｜预案：").append(code.getSuggestion());
        sb.append("｜告警 ").append(a.getAlarmNo());
        return sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
    }

    @Override
    public String disposeNow(String alarmNo, LocalDateTime now) {
        DevAlarm a = byNoScoped(alarmNo);
        if (!OPEN.contains(a.getStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.alarm.closed");
        if (a.getDispositionRef() != null) return a.getDispositionRef();   // 幂等：已处置返回首次单号
        if (!EVAL.equals(a.getSource())) {
            // 存量设备告警：没有业务码与路由，按故障工单处置（取代原先的假单号）
            String woNo = woOps.openOrAttach(new AlarmDraft("FAULT", WoPriority.MEDIUM.name(), "ALM:" + a.getAlarmNo(),
                    a.getCabinetNo(), a.getSiteNo(), "设备告警 " + a.getAlarmCode() + "｜告警 " + a.getAlarmNo(), a.getAlarmNo()));
            DataScopeContext.executeWithoutScope(() -> {
                markDisposed(a, AlarmDisposition.WORK_ORDER, woNo, "工单 " + woNo);
                return null;
            });
            return woNo;
        }
        String r = DataScopeContext.executeWithoutScope(() -> dispose(a, now, null));
        return "AUTO_FIX".equals(r) || "NOTIFY".equals(r) ? null : r;
    }

    @Override
    public DispositionPreview preview(String alarmNo) {
        DevAlarm a = byNoScoped(alarmNo);
        DevAlarmCode code = codes().get(a.getAlarmCode());
        if (code == null) return new DispositionPreview("WORK_ORDER", "FAULT", WoPriority.MEDIUM.name(), null, null, null, null, null);
        DevAlarmRoute route = routeOf(a.getAlarmCode(), a.getCause());
        String type = route != null ? route.getDisposition() : code.getDisposition();
        String woType = route != null && route.getWoType() != null ? route.getWoType() : "FAULT";
        int delta = route != null && route.getPriorityDelta() != null ? route.getPriorityDelta() : 0;
        String p = WoPriority.of(a.getPriority() == null ? code.getBasePriority() : a.getPriority()).plus(delta).name();
        String merge = null, assigneeType = null, assigneeNo = null;
        if (AlarmDisposition.WORK_ORDER.name().equals(type)) {
            String key = mergeKey(a, code, woType, LocalDateTime.now());
            merge = alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getDispositionType, AlarmDisposition.WORK_ORDER.name())
                            .in(DevAlarm::getStatus, OPEN).isNotNull(DevAlarm::getDispositionRef))
                    .stream().filter(x -> key.equals(mergeKey(x, code, woType, LocalDateTime.now()))).map(DevAlarm::getDispositionRef)
                    .findFirst().orElse(null);
            var c = woOps.candidates(a.getSiteNo()).stream().filter(x -> x.siteOwner()).findFirst().orElse(null);
            if (c != null) {
                assigneeType = c.type();
                assigneeNo = c.no();
            }
        }
        return new DispositionPreview(type, AlarmDisposition.WORK_ORDER.name().equals(type) ? woType : null, p, assigneeType,
                assigneeNo, merge, AlarmDisposition.TODO.name().equals(type) ? code.getOwnerRole() : null,
                route == null ? null : route.getFallback());
    }

    @Override
    public void onDispositionDone(String dispositionType, String ref) {
        DataScopeContext.executeWithoutScope(() -> {
            for (DevAlarm a : alarms.selectList(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getDispositionType, dispositionType)
                    .eq(DevAlarm::getDispositionRef, ref).in(DevAlarm::getStatus, OPEN))) {
                if (a.getRecoveredAt() != null || !stillHolds(a)) {
                    close(a, AlarmCloseReason.RESOLVED, dispositionType + " " + ref + " 已完成", SYSTEM);
                } else {
                    // 条件仍成立：清空处置，下一轮 dispatchDue 重新处置（待办允许再建：唯一约束只管未完成的）
                    alarms.update(null, new LambdaUpdateWrapper<DevAlarm>().eq(DevAlarm::getId, a.getId())
                            .set(DevAlarm::getDispositionRef, null).set(DevAlarm::getDispositionType, null));
                    writeLog(a.getAlarmNo(), "DISPOSE", dispositionType + " " + ref + " 已完成，但告警条件仍成立，将重新处置", SYSTEM);
                }
            }
            return null;
        });
    }

    /**
     * 立即对该告警的对象重跑一次判定器：仍产出同一 dedupKey → 仍成立。
     * 工单完工复核（WorkOrderReviewListener）与待办完成共用。
     */
    @Override
    public boolean stillHolds(DevAlarm a) {
        for (StateEvaluator ev : stateEvaluators) {
            if (!ev.codes().contains(a.getAlarmCode())) continue;
            EvalScope scope = ev.siteBatched() && a.getSiteNo() != null
                    ? new EvalScope(siteQuery.briefsByNos(List.of(a.getSiteNo()))) : new EvalScope(List.of());
            return ev.evaluate(scope, LocalDateTime.now()).stream().anyMatch(f -> f.dedupKey().equals(a.getDedupKey()));
        }
        return false;   // 事件型码：没有持续状态可复核
    }

    /** 安全兜底（07 §3.1）：信号持有的锁仓还在、却没有未关闭的电池隐患告警 → 补开（事件可能丢了）。 */
    private void backfillSafetyLocks(Map<String, DevAlarmCode> codes, LocalDateTime now, Set<String> only) {
        DevAlarmCode code = codes.get(SafetySignalEvaluator.BATTERY_HAZARD);
        if (code == null || !on(code.getEnabled())) return;
        for (DeviceProtectionPort.SignalLock l : protection.activeSignalLocks(200)) {
            if (only != null && (l.siteNo() == null || !only.contains(l.siteNo()))) continue;
            long open = alarms.selectCount(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getAlarmCode, SafetySignalEvaluator.BATTERY_HAZARD)
                    .eq(DevAlarm::getCabinetNo, l.cabinetNo()).in(DevAlarm::getStatus, OPEN));
            if (open > 0) continue;
            Finding f = new Finding(SafetySignalEvaluator.BATTERY_HAZARD, AlarmSubjectType.SLOT, l.cabinetNo() + "#" + l.slotIndex(),
                    l.siteNo(), l.cabinetNo(), l.agentNo(), null, AlarmCause.HAZARD, ai.neargo.sharehub.alarm.ImpactScope.SLOT, 0,
                    List.of(new Finding.Evidence("SLOT_LOCK", l.cabinetNo(), l.slotIndex(), now, "锁仓仍生效，补开")), Map.of());
            List<String> due = tx.execute(st -> {
                DevAlarm a = open(f, code, site(l.siteNo()), now, now);
                return a == null ? List.<String>of() : List.of(a.getAlarmNo());
            });
            if (due != null) due.forEach(no -> disposeSafely(no, now));
        }
    }

    // ═══════════════════════════ 工具 ═══════════════════════════

    private Map<String, DevAlarmCode> codes() {
        return codeMapper.selectList(new LambdaQueryWrapper<DevAlarmCode>().isNull(DevAlarmCode::getArchivedAt))
                .stream().collect(Collectors.toMap(DevAlarmCode::getCode, c -> c, (a, b) -> a));
    }

    private DevAlarmRoute routeOf(String code, String cause) {
        List<DevAlarmRoute> rs = routes.selectList(new LambdaQueryWrapper<DevAlarmRoute>().eq(DevAlarmRoute::getAlarmCode, code));
        return rs.stream().filter(r -> Objects.equals(r.getCause(), cause)).findFirst()
                .orElse(rs.stream().filter(r -> "*".equals(r.getCause())).findFirst().orElse(null));
    }

    private DevAlarm openOf(String dedupKey) {
        return alarms.selectOne(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getSource, EVAL).eq(DevAlarm::getDedupKey, dedupKey)
                .in(DevAlarm::getStatus, OPEN).last("limit 1"));
    }

    private DevAlarm byNo(String alarmNo) {
        return DataScopeContext.executeWithoutScope(() -> alarms.selectOne(new LambdaQueryWrapper<DevAlarm>()
                .eq(DevAlarm::getAlarmNo, alarmNo).last("limit 1")));
    }

    private DevAlarm byNoScoped(String alarmNo) {
        DevAlarm a = alarms.selectOne(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getAlarmNo, alarmNo).last("limit 1"));
        if (a == null) throw BizException.notFound(alarmNo);
        return a;
    }

    private SiteBrief site(String siteNo) {
        if (siteNo == null) return null;
        return siteQuery.briefsByNos(List.of(siteNo)).stream().findFirst().orElse(null);
    }

    private DevAlarmSiteProfile profileOf(String siteNo) {
        if (siteNo == null) return null;
        return profiles.selectOne(new LambdaQueryWrapper<DevAlarmSiteProfile>().eq(DevAlarmSiteProfile::getSiteNo, siteNo).last("limit 1"));
    }

    private void writeLog(String alarmNo, String event, String note, String operator) {
        DevAlarmLog l = new DevAlarmLog();
        l.setAlarmNo(alarmNo);
        l.setEvent(event);
        l.setNote(note == null ? null : note.length() > 512 ? note.substring(0, 512) : note);
        l.setOperator(operator == null ? SYSTEM : operator);
        logs.insert(l);
    }

    private static String evidenceJson(Collection<Finding.Evidence> ev) {
        if (ev == null || ev.isEmpty()) return "[]";
        String json = ev.stream().limit(20).map(e -> "{\"signal\":" + q(e.signal()) + ",\"cabinetNo\":" + q(e.cabinetNo())
                        + ",\"slot\":" + (e.slot() == null ? "null" : e.slot()) + ",\"at\":" + q(e.at() == null ? null : e.at().toString())
                        + ",\"note\":" + q(e.note()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
        return json.length() > 2000 ? json.substring(0, 1990) + "]" : json;
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Integer slotOf(String evidence) {
        if (evidence == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"slot\":(\\d+)").matcher(evidence);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static boolean on(Integer flag) {
        return flag != null && flag == 1;
    }
}
