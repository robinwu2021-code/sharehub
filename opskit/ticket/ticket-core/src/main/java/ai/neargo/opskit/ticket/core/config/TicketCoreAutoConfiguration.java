package ai.neargo.opskit.ticket.core.config;

import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.timer.TimerScheduler;
import ai.neargo.opskit.process.client.InstanceObserver;
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.ticket.core.app.TicketDispatcher;
import ai.neargo.opskit.ticket.core.app.TicketAppService;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
import ai.neargo.opskit.ticket.core.app.TicketObserver;
import org.springframework.context.annotation.Lazy;
import ai.neargo.opskit.ticket.store.TicketMigrations;
import ai.neargo.opskit.ticket.store.TicketStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionOperations;

/** 工单层装配。排在流程层之后；只经流程 / 平台的客户端接口（ProcessClient · DirectoryClient），按类名字符串排序、不引用下层 core。 */
@AutoConfiguration(afterName = "ai.neargo.opskit.process.core.config.ProcessCoreAutoConfiguration")
public class TicketCoreAutoConfiguration {

    /** 工单表迁移（独立历史表）。 */
    public record TicketSchema(int appliedMigrations) {
    }

    @Bean
    TicketSchema ticketSchema(DataSource dataSource) {
        return new TicketSchema(TicketMigrations.migrate(dataSource).migrationsExecuted);
    }

    @Bean
    TicketStore ticketStore(DataSource dataSource, TicketSchema schema) {
        return TicketStore.create(dataSource);
    }

    @Bean
    TicketDefinitionAppService ticketDefinitionAppService(TicketStore s, ProcessClient processes, DirectoryClient directory, AuditRecorder audit,
            Clock clock, TransactionOperations tx) {
        return new TicketDefinitionAppService(s.definitions(), processes, directory, audit, clock, tx);
    }

    @Bean
    TicketDispatcher ticketDispatcher(TaskClient tasks, DirectoryClient directory, TicketStore s, EventPublisher events, TimerScheduler timers, Clock clock) {
        return new TicketDispatcher(tasks, directory, s.cursors(), s.tickets(), events, timers, clock);
    }

    @Bean
    TicketAppService ticketAppService(TicketDefinitionAppService defs, TicketStore s, ProcessClient processes, TaskClient tasks, TicketDispatcher dispatcher,
            BizNoGenerator ids, Clock clock, TransactionOperations tx) {
        return new TicketAppService(defs, s.tickets(), s.fieldIndex(), s.scopes(), s.comments(), processes, tasks, dispatcher, ids, clock, tx);
    }

    /**
     * 流程 → 工单快照同步。流程层装配时就要拿到观察者列表，而工单服务又依赖流程客户端 —— 用 @Lazy 代理打断创建期的环。
     */
    @Bean
    InstanceObserver ticketObserver(@Lazy TicketAppService tickets) {
        return new TicketObserver(tickets);
    }
}
