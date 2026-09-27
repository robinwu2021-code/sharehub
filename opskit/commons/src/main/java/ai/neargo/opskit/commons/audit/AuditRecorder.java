package ai.neargo.opskit.commons.audit;

import ai.neargo.opskit.commons.actor.Actor;
import java.util.Map;

/**
 * 审计的抽象（实现在平台层）。流程、工单模块按分层规则不能依赖平台 core，经这个接口写审计。
 * 必须在被审计变更的同一事务里调用。
 *
 * @param workspaceId 空串 = 账户级（如发布账户模板）
 */
public interface AuditRecorder {

    void record(Actor actor, String workspaceId, String action, String target, Map<String, ?> detail);
}
