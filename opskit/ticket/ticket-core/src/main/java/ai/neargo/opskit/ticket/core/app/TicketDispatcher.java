package ai.neargo.opskit.ticket.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.timer.TimerId;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef.Strategy;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import ai.neargo.opskit.ticket.api.event.AssignmentRequested;
import ai.neargo.opskit.ticket.store.dao.AssignCursorDao;
import ai.neargo.opskit.ticket.store.dao.TicketDao;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 派单策略（21/07 O3-5 · 21/02 §7.1）。建单后（或外部指定超时后）按类型的策略给实例建办理任务。须在调用方事务里调用。
 *
 * <ul>
 *   <li>MANUAL：不建任务，等有 ticket:assign 的人派。</li>
 *   <li>QUEUE：候选 = 组，组员领取。</li>
 *   <li>ROUND_ROBIN：候选 = 组，并按游标轮转直接派给下一位（游标行加锁，并发不重复）。退回池时回到组里。</li>
 *   <li>LEAST_LOADED：同上，派给组内未结办理任务最少者；同分按轮转。</li>
 *   <li>FIELD：派给工单字段里的成员；字段为空则不派（留给人工）。</li>
 *   <li>EXTERNAL：发 ticket.assignment_requested，等接入方回填；超时走兜底策略。</li>
 * </ul>
 * 组里一个人都没有时，轮转 / 最少负载退回为队列（任务挂在组上，有人入组即可领），并记警告。
 */
public class TicketDispatcher {

    public static final String ASSIGN_TIMEOUT_TASK = "assign-timeout";
    private static final Logger log = LoggerFactory.getLogger(TicketDispatcher.class);

    private final TaskClient tasks;
    private final DirectoryClient directory;
    private final AssignCursorDao cursors;
    private final TicketDao tickets;
    private final EventPublisher events;
    private final TimerScheduler timers;
    private final Clock clock;

    public TicketDispatcher(TaskClient tasks, DirectoryClient directory, AssignCursorDao cursors, TicketDao tickets, EventPublisher events,
            TimerScheduler timers, Clock clock) {
        this.tasks = tasks;
        this.directory = directory;
        this.cursors = cursors;
        this.tickets = tickets;
        this.events = events;
        this.timers = timers;
        this.clock = clock;
    }

    /** 按类型的派单策略派单。@return 建出的任务号；MANUAL / EXTERNAL / 字段为空时为空 */
    @SystemAction("建单内部调用")
    public Optional<String> dispatch(TenantContext t, TicketDao.Row ticket, TicketTypeDef def) {
        return dispatch(t, ticket, def, def.assignment().strategy());
    }

    /** 外部指定超时（定时触发）：仍未派出且未结时按兜底策略派。已派 / 已关 → 无操作。 */
    @SystemAction("定时触发")
    public Optional<String> fallback(TenantContext t, TicketDao.Row ticket, TicketTypeDef def) {
        if (!ticket.open() || ticket.assignee() != null || hasOpenWork(t, ticket)) {
            return Optional.empty();
        }
        return dispatch(t, ticket, def, def.assignment().fallback());
    }

    private Optional<String> dispatch(TenantContext t, TicketDao.Row ticket, TicketTypeDef def, Strategy strategy) {
        AssignmentDef a = def.assignment();
        return switch (strategy) {
            case MANUAL -> Optional.empty();
            case QUEUE -> Optional.of(queueTask(t, ticket, a.group()));
            case FIELD -> {
                Object v = ticket.fields().get(a.field());
                yield v instanceof String m ? Optional.of(created(t, ticket, tasks.createWork(t, ticket.instanceNo(), List.of(m), null, ticket.title(),
                        ticket.priorityOrd(), "TICKET"))) : Optional.empty();
            }
            case ROUND_ROBIN, LEAST_LOADED -> {
                List<String> members = directory.membersOf(t, a.group()).stream().sorted().toList();
                if (members.isEmpty()) {
                    log.warn("派单组里没有成员，退回为队列 group={} ticketNo={}，需给该组添加成员", a.group(), ticket.ticketNo());
                    yield Optional.of(queueTask(t, ticket, a.group()));
                }
                List<String> pool = strategy == Strategy.LEAST_LOADED ? leastLoaded(t, members) : members;
                String last = cursors.lockLast(t.workspaceId(), def.code(), a.group());
                String next = pool.stream().filter(m -> last == null || m.compareTo(last) > 0).findFirst().orElse(pool.get(0));
                cursors.advance(t.workspaceId(), def.code(), a.group(), next);
                String taskNo = queueTask(t, ticket, a.group());
                tasks.assignTo(internal(t), taskNo, "member:" + next, null);
                yield Optional.of(taskNo);
            }
            case EXTERNAL -> {
                long at = clock.millis() + a.externalTimeoutSec() * 1000L;
                events.publish(new AssignmentRequested(t, ticket.ticketNo(), def.code(), ticket.instanceNo(), ticket.fields(), at, clock.instant()));
                timers.schedule(ASSIGN_TIMEOUT_TASK, new TimerId(t.workspaceId(), ticket.ticketNo(), 0), Instant.ofEpochMilli(at));
                yield Optional.empty();
            }
        };
    }

    /** 组内未结办理任务最少的那些人（同分的都留下，交给轮转挑）。 */
    private List<String> leastLoaded(TenantContext t, List<String> members) {
        Map<String, Long> load = tasks.openWorkload(t, members.stream().map(m -> "member:" + m).toList());
        long min = members.stream().mapToLong(m -> load.getOrDefault("member:" + m, 0L)).min().orElse(0);
        return members.stream().filter(m -> load.getOrDefault("member:" + m, 0L) == min).sorted(Comparator.naturalOrder()).toList();
    }

    private String queueTask(TenantContext t, TicketDao.Row ticket, String group) {
        return created(t, ticket, tasks.createWork(t, ticket.instanceNo(), List.of("group:" + group), group, ticket.title(), ticket.priorityOrd(), "TICKET"));
    }

    private String created(TenantContext t, TicketDao.Row ticket, String taskNo) {
        tickets.setCurrentTask(t.workspaceId(), ticket.ticketNo(), taskNo);
        return taskNo;
    }

    private boolean hasOpenWork(TenantContext t, TicketDao.Row ticket) {
        return !tasks.openWorkTasks(internal(t), ticket.instanceNo()).isEmpty();
    }

    static Actor internal(TenantContext t) {
        return new Actor(t, Optional.empty(), Optional.of("opskit-ticket"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
