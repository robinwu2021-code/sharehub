package ai.neargo.opskit.ticket.core.config;

import ai.neargo.opskit.commons.audit.AuditRecorder;
import ai.neargo.opskit.platform.client.DirectoryClient;
import ai.neargo.opskit.process.client.ProcessClient;
import ai.neargo.opskit.ticket.core.app.TicketDefinitionAppService;
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
}
