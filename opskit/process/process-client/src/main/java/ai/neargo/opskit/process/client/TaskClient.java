package ai.neargo.opskit.process.client;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.model.TaskView;
import java.util.List;

/** 任务能力的客户端（领取 · 放回 · 指派 / 转派 · 退回池）。 */
public interface TaskClient {

    TaskView claim(Actor actor, String taskNo);

    TaskView release(Actor actor, String taskNo);

    /** @param memberRef {@code member:x}；已有办理人时为转派，须写原因 */
    TaskView assignTo(Actor actor, String taskNo, String memberRef, String reason);

    TaskView pool(Actor actor, String taskNo, String reason);

    /** 实例上未结束的办理任务（WORK）。 */
    List<TaskView> openWorkTasks(Actor actor, String instanceNo);
}
