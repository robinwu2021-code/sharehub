package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.ApprovalOutcomeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按业务类型找回写实现；同一类型注册两个直接启动失败（两边都回写只会互相覆盖） */
@Component
public class ApprovalOutcomeHandlers {

    private final Map<String, ApprovalOutcomeHandler> byType;

    public ApprovalOutcomeHandlers(List<ApprovalOutcomeHandler> all) {
        this.byType = all.stream().collect(Collectors.toMap(ApprovalOutcomeHandler::bizType, Function.identity()));
    }

    public Optional<ApprovalOutcomeHandler> find(String bizType) {
        return Optional.ofNullable(bizType == null ? null : byType.get(bizType));
    }

    public ApprovalOutcomeHandler require(String bizType) {
        return find(bizType).orElseThrow(() -> new IllegalStateException("没有业务类型 " + bizType + " 的审批回写实现"));
    }
}
