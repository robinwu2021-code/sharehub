package ai.neargo.opskit.process.core.embedded;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.process.client.TaskClient;
import ai.neargo.opskit.process.core.app.TaskAppService;
import java.util.List;

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
}
