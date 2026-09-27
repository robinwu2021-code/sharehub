package ai.neargo.opskit.process.api.definition;

import java.util.Objects;

/** 迁移的后置动作：固定集合，与迁移同事务执行（21/02 §6.1）。派单策略属于工单，这里只做「派给谁」这一步。 */
public sealed interface ActionDef {

    /** 给新状态建办理任务，候选为 {@code candidates}。 */
    record AssignTo(ApproverRef candidates) implements ActionDef {
        public AssignTo {
            Objects.requireNonNull(candidates, "candidates");
        }
    }

    record SetField(String field, Object value) implements ActionDef {
    }

    /** 把 {@code fromField} 字段里的主体加为 {@code role} 参与者。 */
    record AddParticipant(String role, String fromField) implements ActionDef {
    }

    record ClearAssignee() implements ActionDef {
    }
}
