package ai.neargo.opskit.svc.wiring;

import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.calendar.BusinessCalendar;
import ai.neargo.opskit.platform.core.app.CalendarAppService;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.core.app.SlaTracker;
import ai.neargo.opskit.process.core.port.Calendars;
import java.util.Optional;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations;
import ai.neargo.opskit.process.core.app.ProcessTimers;
import ai.neargo.opskit.process.core.app.TaskAppService;
import ai.neargo.opskit.process.core.port.Directory;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动模块的接线：目录端口 + 流程层的定时任务（流程层不依赖 db-scheduler，任务在这里注册）。
 * 一次性任务不带数据，信息都在实例 ID（{@code ws:key:epoch}）里。
 */
@Configuration(proxyBeanMethods = false)
public class SvcWiring {

    private static final Logger log = LoggerFactory.getLogger(SvcWiring.class);

    @Bean
    Directory processDirectory(DirectoryAppService directory) {
        return new PlatformDirectory(directory);
    }

    /** SLA 按工作日历计时：取平台日历；未配置的日历为空（计时器退回自然时间并记警告）。 */
    @Bean
    Calendars processCalendars(CalendarAppService calendars) {
        return (t, code) -> {
            try {
                return Optional.of(new BusinessCalendar(calendars.get(t, code)));
            } catch (OpsKitException e) {
                return Optional.empty();
            }
        };
    }

    @Bean
    Task<Void> slaDueTask(ProcessTimers timers) {
        return Tasks.oneTime(SlaTracker.DUE_TASK).execute((inst, ctx) -> timers.slaDue(inst.getId()));
    }

    @Bean
    Task<Void> slaWarnTask(ProcessTimers timers) {
        return Tasks.oneTime(SlaTracker.WARN_TASK).execute((inst, ctx) -> timers.slaWarn(inst.getId()));
    }

    /** 兜底：到期 / 预警定时丢了也会补上（21/02 §8.5，每 10 分钟）。 */
    @Bean
    Task<Void> slaReconcileTask(ProcessTimers timers) {
        return Tasks.recurring("sla-reconcile", FixedDelay.of(Duration.ofMinutes(10))).execute((inst, ctx) -> {
            int n = timers.reconcileSla(500);
            if (n > 0) {
                log.info("SLA 兜底扫描补触发 count={}", n);
            }
        });
    }

    @Bean
    Task<Void> claimTimeoutTask(ProcessTimers timers) {
        return Tasks.oneTime(TaskAppService.CLAIM_TIMEOUT_TASK).execute((inst, ctx) -> timers.claimTimeout(inst.getId()));
    }

    @Bean
    Task<Void> confirmTimeoutTask(ProcessTimers timers) {
        return Tasks.oneTime(GateTimersAndConfirmations.CONFIRM_TIMEOUT_TASK).execute((inst, ctx) -> timers.confirmTimeout(inst.getId()));
    }

    @Bean
    Task<Void> gateTimerTask(ProcessTimers timers) {
        return Tasks.oneTime(GateTimersAndConfirmations.GATE_TIMER_TASK).execute((inst, ctx) -> timers.gateTimer(inst.getId()));
    }

    /** 兜底：调度丢失时补放回超时的领取（21/02 §九）。 */
    @Bean
    Task<Void> staleClaimSweepTask(ProcessTimers timers) {
        return Tasks.recurring("stale-claim-sweep", FixedDelay.of(Duration.ofMinutes(1))).execute((inst, ctx) -> {
            int n = timers.sweepStaleClaims(500);
            if (n > 0) {
                log.info("兜底扫描放回超时领取 count={}", n);
            }
        });
    }
}
