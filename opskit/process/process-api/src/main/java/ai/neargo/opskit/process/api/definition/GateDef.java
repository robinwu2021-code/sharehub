package ai.neargo.opskit.process.api.definition;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 状态上的门（21/02 §6.2）：进入状态时启动，结束时自动触发配置好的**系统迁移**。一个状态至多一个门。
 */
public sealed interface GateDef {

    /** 门结束时可能触发的迁移代号（定义校验要求它们都存在、为 system、且从门所在状态出发）。 */
    List<String> exits();

    record Approval(List<ApprovalStepDef> steps, ApprovalRules rules, String onApproved, String onRejected, String onWithdrawn) implements GateDef {
        public Approval {
            steps = steps == null ? List.of() : List.copyOf(steps);
            rules = rules == null ? ApprovalRules.DEFAULT : rules;
        }

        @Override
        public List<String> exits() {
            return java.util.stream.Stream.of(onApproved, onRejected, onWithdrawn).filter(Objects::nonNull).toList();
        }
    }

    /** 外部确认：发事件等接入方异步回复（业务前置校验的通用落法）。 */
    record External(String topic, Duration timeout, String onConfirmed, String onRejected, String onTimeout) implements GateDef {
        @Override
        public List<String> exits() {
            return java.util.stream.Stream.of(onConfirmed, onRejected, onTimeout).filter(Objects::nonNull).toList();
        }
    }

    /** 定时：在状态停留满时长后自动迁移（如「已解决 7 天后自动关闭」）。 */
    record Timer(Duration after, String onElapsed) implements GateDef {
        @Override
        public List<String> exits() {
            return onElapsed == null ? List.of() : List.of(onElapsed);
        }
    }
}
