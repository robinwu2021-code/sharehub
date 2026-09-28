package ai.neargo.opskit.process.core.config;

import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.process.core.app.ApprovalAppService;
import ai.neargo.opskit.process.core.app.ApprovalTemplateAppService;
import ai.neargo.opskit.process.core.app.GateTimersAndConfirmations;
import ai.neargo.opskit.process.core.app.InstanceAppService;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.core.app.ProcessHistoryService;
import ai.neargo.opskit.process.core.app.ProcessQueryService;
import ai.neargo.opskit.process.client.InstanceObserver;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.process.core.app.ObserverHook;
import ai.neargo.opskit.process.core.app.ProcessTimers;
import ai.neargo.opskit.process.core.embedded.EmbeddedProcessClient;
import ai.neargo.opskit.process.core.embedded.EmbeddedTaskClient;
import ai.neargo.opskit.process.core.app.SlaPolicyAppService;
import ai.neargo.opskit.process.core.app.SlaTracker;
import ai.neargo.opskit.process.core.port.Calendars;
import ai.neargo.opskit.process.core.app.TaskAppService;
import ai.neargo.opskit.process.core.port.Directory;
import ai.neargo.opskit.process.store.ProcessMigrations;
import ai.neargo.opskit.process.store.ProcessStore;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 流程层装配。排在平台层之后（用它的时钟、取号、事务、发件箱、定时器、审计）；按分层规则只以类名字符串排序，不引用平台类。
 * {@link Directory} 由启动模块提供（把平台目录接进来）。db-scheduler 的任务也由启动模块注册 —— 流程层不依赖调度器。
 */
@AutoConfiguration(afterName = "ai.neargo.opskit.platform.core.config.PlatformCoreAutoConfiguration")
public class ProcessCoreAutoConfiguration {

    /** 流程表迁移（独立历史表）；其他 Bean 依赖它，保证先建表。 */
    public record ProcessSchema(int appliedMigrations) {
    }

    @Bean
    ProcessSchema processSchema(DataSource dataSource) {
        return new ProcessSchema(ProcessMigrations.migrate(dataSource).migrationsExecuted);
    }

    @Bean
    ProcessStore processStore(DataSource dataSource, ProcessSchema schema) {
        return ProcessStore.create(dataSource);
    }

    @Bean
    ProcessDefinitionAppService processDefinitionAppService(ProcessStore s, AuditRecorder audit, Clock clock, TransactionOperations tx) {
        return new ProcessDefinitionAppService(s.definitions(), audit, clock, tx);
    }

    @Bean
    TaskAppService taskAppService(ProcessStore s, Directory directory, TimerScheduler timers, EventPublisher events, BizNoGenerator ids, Clock clock,
            TransactionOperations tx) {
        return new TaskAppService(s.tasks(), s.participants(), directory, timers, events, ids, clock, tx, s.logs());
    }

    @Bean
    ApprovalAppService approvalAppService(ProcessDefinitionAppService defs, ProcessStore s, TaskAppService tasks, ObjectProvider<InstanceAppService> instances,
            Directory directory, EventPublisher events, BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        return new ApprovalAppService(defs, s.approvals(), s.tasks(), s.instances(), s.participants(), s.logs(), tasks, instances::getObject, directory,
                events, ids, clock, tx);
    }

    @Bean
    GateTimersAndConfirmations gateTimersAndConfirmations(ProcessDefinitionAppService defs, ProcessStore s, ObjectProvider<InstanceAppService> instances,
            TimerScheduler timers, EventPublisher events, Clock clock, TransactionOperations tx) {
        return new GateTimersAndConfirmations(defs, s.instances(), instances::getObject, timers, events, clock, tx);
    }

    @Bean
    SlaPolicyAppService slaPolicyAppService(ProcessStore s, AuditRecorder audit, Clock clock, TransactionOperations tx) {
        return new SlaPolicyAppService(s.definitions(), audit, clock, tx);
    }

    /** 没接日历端口时按自然时间计（引用了日历的策略会记警告）。 */
    @Bean
    SlaTracker slaTracker(SlaPolicyAppService policies, ProcessStore s, ObjectProvider<Calendars> calendars, TimerScheduler timers, EventPublisher events,
            BizNoGenerator ids, Clock clock, TransactionOperations tx, ObjectProvider<InstanceObserver> observers) {
        return new SlaTracker(policies, s.slaClocks(), s.logs(), calendars.getIfAvailable(() -> Calendars.NONE), timers, events, ids, clock, tx,
                observers.orderedStream().toList());
    }

    @Bean
    InstanceAppService instanceAppService(ProcessDefinitionAppService defs, ProcessStore s, TaskAppService tasks, ApprovalAppService approvals,
            GateTimersAndConfirmations gates, SlaTracker sla, ObjectProvider<InstanceObserver> observers, EventPublisher events, BizNoGenerator ids,
            Clock clock, TransactionOperations tx) {
        // ObserverHook 排最后：门、任务、SLA 都处理完，上层读到的是完整状态
        return new InstanceAppService(defs, s.instances(), s.participants(), s.scopes(), s.logs(),
                List.of(tasks, approvals, gates, sla, new ObserverHook(observers.orderedStream().toList())), Optional.of(tasks), events, ids, clock, tx);
    }

    @Bean
    ProcessClient processClient(InstanceAppService instances, ProcessDefinitionAppService defs, SlaPolicyAppService policies, SlaTracker sla,
            ProcessStore s) {
        return new EmbeddedProcessClient(instances, defs, policies, sla, s.logs());
    }

    @Bean
    TaskClient taskClient(TaskAppService tasks) {
        return new EmbeddedTaskClient(tasks);
    }

    @Bean
    ApprovalTemplateAppService approvalTemplateAppService(ProcessStore s, ProcessDefinitionAppService defs, AuditRecorder audit, Clock clock,
            TransactionOperations tx) {
        return new ApprovalTemplateAppService(s.definitions(), defs, s.queries(), audit, clock, tx);
    }

    @Bean
    ProcessQueryService processQueryService(ProcessDefinitionAppService defs, ProcessStore s, Directory directory) {
        return new ProcessQueryService(defs, s.queries(), directory, s.slaClocks());
    }

    @Bean
    ProcessHistoryService processHistoryService(ProcessDefinitionAppService defs, ProcessStore s) {
        return new ProcessHistoryService(defs, s.queries(), s.logs());
    }

    @Bean
    ProcessTimers processTimers(TaskAppService tasks, GateTimersAndConfirmations gates, SlaTracker sla, ProcessStore s, Directory directory, Clock clock) {
        return new ProcessTimers(tasks, gates, sla, s.tasks(), s.instances(), s.slaClocks(), directory, clock);
    }
}
