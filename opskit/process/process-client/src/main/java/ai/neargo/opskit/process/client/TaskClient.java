package ai.neargo.opskit.process.client;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 任务能力的客户端（领取 · 放回 · 指派 / 转派 · 退回池）。 */
public interface TaskClient {

    TaskView claim(Actor actor, String taskNo);

    TaskView release(Actor actor, String taskNo);

    /** @param memberRef {@code member:x}；已有办理人时为转派，须写原因 */
    TaskView assignTo(Actor actor, String taskNo, String memberRef, String reason);

    TaskView pool(Actor actor, String taskNo, String reason);

    /** 实例上未结束的办理任务（WORK）。 */
    List<TaskView> openWorkTasks(Actor actor, String instanceNo);

    /**
     * 给实例建一条办理任务（上层按自己的派单策略决定候选）。唯一候选是具体成员时直接指派。须在调用方事务里调用。
     *
     * @param candidates {@code member:x} / {@code group:y} …
     * @param queueGroup 候选是队列组时填组代号（队列页、领取超时用）
     * @param origin     待办来源：PROCESS / TICKET
     * @return 任务号
     */
    String createWork(TenantContext t, String instanceNo, List<String> candidates, String queueGroup, String title, Integer priorityOrd, String origin);

    /** 成员名下未结的办理任务数；没有任务的成员不在结果里。 */
    Map<String, Long> openWorkload(TenantContext t, Collection<String> memberRefs);
}
