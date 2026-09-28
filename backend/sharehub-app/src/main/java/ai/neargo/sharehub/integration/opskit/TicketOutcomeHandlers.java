package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.TicketOutcomeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按业务类型找工单回写实现（送单、命令执行、webhook 三处共用）；同一类型注册两个直接启动失败 */
@Component
public class TicketOutcomeHandlers {

    private final Map<String, TicketOutcomeHandler> byType;

    public TicketOutcomeHandlers(List<TicketOutcomeHandler> all) {
        this.byType = all.stream().collect(Collectors.toMap(TicketOutcomeHandler::bizType, Function.identity()));
    }

    public Optional<TicketOutcomeHandler> find(String bizType) {
        return Optional.ofNullable(bizType == null ? null : byType.get(bizType));
    }
}
