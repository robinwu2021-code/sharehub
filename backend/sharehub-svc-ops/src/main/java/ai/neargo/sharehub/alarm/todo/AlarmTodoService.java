package ai.neargo.sharehub.alarm.todo;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmTodo;
import ai.neargo.sharehub.alarm.entity.DevAlarm;

/**
 * 告警待办（TDD/05 §7.4）：合作 / 经营 / 资金类告警的处置落点（经营看板 › 待办中心）。
 * 一条告警同时最多一条未完成待办（数据库唯一约束）；完成后若条件仍成立，下一轮会再建一条。
 */
public interface AlarmTodoService {

    /** 幂等：该告警已有未完成待办则返回它。 */
    AlarmTodo create(DevAlarm alarm, String roleCode);

    /** @param mine true = 只看当前用户角色 / 本人承接的 */
    PageResult<AlarmTodo> page(boolean mine, String status, Integer page, Integer size);

    /** 完成待办 → 告警按「条件是否仍成立」决定关闭或下一轮再建待办。 */
    AlarmTodo done(String todoNo, String note);

    /** 系统撤销（告警自愈 / 被取代）。 */
    void cancel(String todoNo, String reason);

    long openCountForMe();
}
