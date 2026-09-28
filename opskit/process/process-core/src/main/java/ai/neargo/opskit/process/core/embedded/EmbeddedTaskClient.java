package ai.neargo.opskit.process.core.embedded;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.process.core.app.TaskAppService;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** {@link TaskClient} 的进程内实现。 */
public class EmbeddedTaskClient implements TaskClient {

    private final TaskAppService tasks;

    public EmbeddedTaskClient(TaskAppService tasks) {
        this.tasks = tasks;
    }

    @Override
    public TaskView claim(Actor actor, String taskNo) {
        return tasks.claim(actor, taskNo);
    }

    @Override
    public TaskView release(Actor actor, String taskNo) {
        return tasks.release(actor, taskNo);
    }

    @Override
    public TaskView assignTo(Actor actor, String taskNo, String memberRef, String reason) {
        return tasks.assignTo(actor, taskNo, memberRef, reason);
    }

    @Override
    public TaskView pool(Actor actor, String taskNo, String reason) {
        return tasks.pool(actor, taskNo, reason);
    }

    @Override
    public List<TaskView> openWorkTasks(Actor actor, String instanceNo) {
        return tasks.openWorkTasks(actor.tenant(), instanceNo);
    }

    @Override
    public String createWork(TenantContext t, String instanceNo, List<String> candidates, String queueGroup, String title, Integer priorityOrd,
            String origin) {
        return tasks.create(t, instanceNo, TaskAppService.WORK, null, null, null, queueGroup, title, priorityOrd, null, candidates, origin);
    }

    @Override
    public Map<String, Long> openWorkload(TenantContext t, Collection<String> memberRefs) {
        return tasks.openWorkload(t, memberRefs);
    }
}
