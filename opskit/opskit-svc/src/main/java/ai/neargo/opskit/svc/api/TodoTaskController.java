package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.error.ErrorKey;
import ai.neargo.opskit.commons.query.Page;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.process.api.model.TaskView;
import ai.neargo.opskit.process.api.model.query.QueueCount;
import ai.neargo.opskit.process.api.model.query.QueueTask;
import ai.neargo.opskit.process.api.model.query.TaskQuery;
import ai.neargo.opskit.process.api.model.query.TodoCount;
import ai.neargo.opskit.process.api.model.query.TodoItem;
import ai.neargo.opskit.process.api.model.query.TodoQuery;
import ai.neargo.opskit.process.core.app.ApprovalAppService;
import ai.neargo.opskit.process.core.app.ProcessQueryService;
import ai.neargo.opskit.process.core.app.TaskAppService;
import ai.neargo.opskit.svc.api.Present.Named;
import ai.neargo.opskit.svc.api.Present.People;
import ai.neargo.opskit.svc.api.Present.Person;
import ai.neargo.opskit.svc.api.Present.Subject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 待办 · 任务 / 队列（21/06 §四）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class TodoTaskController {

    static final ErrorKey COMPLETE_BY_TRANSITION = new ErrorKey("opskit.task.complete_by_transition", 422);

    private final ProcessQueryService queries;
    private final TaskAppService tasks;
    private final ApprovalAppService approvals;
    private final DirectoryAppService directory;

    public TodoTaskController(ProcessQueryService queries, TaskAppService tasks, ApprovalAppService approvals, DirectoryAppService directory) {
        this.queries = queries;
        this.tasks = tasks;
        this.approvals = approvals;
        this.directory = directory;
    }

    public record TodoRow(String taskNo, String kind, String origin, String title, Subject subject, Named process, Integer priorityOrd,
            long createdAtMs, Long dueAtMs, boolean claimable, String instanceNo, String approvalNo, String queueGroup) {
    }

    public record TaskRow(String taskNo, String kind, String status, Named queue, String title, Subject subject, Integer priorityOrd,
            long createdAtMs, String instanceNo, String approvalNo, Person claimedBy, Long claimExpiresAtMs) {
    }

    public record QueueCountRow(Named queue, long unclaimed, long claimedByMe) {
    }

    public record CompleteBody(String decision, String reason) {
    }

    @PostMapping("/todos/query")
    public Page<TodoRow> todos(Actor actor, @RequestBody(required = false) TodoQuery q, HttpServletRequest req) {
        String locale = Present.locale(req);
        return queries.todos(actor, q == null ? new TodoQuery(null, null, null, null) : q).map(t -> new TodoRow(t.taskNo(), t.kind(), t.origin(),
                t.title() != null ? t.title() : Present.text(t.processName(), locale), Present.subject(t.subject()),
                new Named(null, Present.text(t.processName(), locale), t.processName() == null ? Map.of() : t.processName().values()),
                t.priorityOrd(), t.createdAtMs(), t.dueAtMs(), t.claimable(), t.instanceNo(), t.approvalNo(), t.queueGroup()));
    }

    @PostMapping("/todos/count")
    public TodoCount todoCount(Actor actor) {
        return queries.todoCount(actor);
    }

    @PostMapping("/tasks/query")
    public Page<TaskRow> tasks(Actor actor, @RequestBody(required = false) TaskQuery q, HttpServletRequest req) {
        Page<QueueTask> page = queries.tasks(actor, q == null ? new TaskQuery(null, null, null, null, null, null) : q);
        People people = new People(directory, actor.tenant(), page.list().stream().map(QueueTask::claimedBy).toList());
        Map<String, Named> groups = new HashMap<>();
        String locale = Present.locale(req);
        return page.map(t -> new TaskRow(t.taskNo(), t.kind(), t.status(), queue(actor, t.queueGroup(), locale, groups), t.title(),
                Present.subject(t.subject()), t.priorityOrd(), t.createdAtMs(), t.instanceNo(), t.approvalNo(), people.of(t.claimedBy()),
                t.claimExpiresAtMs()));
    }

    @PostMapping("/tasks/queue-counts")
    public List<QueueCountRow> queueCounts(Actor actor, HttpServletRequest req) {
        Map<String, Named> groups = new HashMap<>();
        String locale = Present.locale(req);
        return queries.queueCounts(actor).stream()
                .map((QueueCount c) -> new QueueCountRow(queue(actor, c.queueGroup(), locale, groups), c.unclaimed(), c.claimedByMe())).toList();
    }

    @PostMapping("/tasks/{no}/claim")
    public TaskRow claim(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        return taskRow(actor, tasks.claim(actor, no), req);
    }

    @PostMapping("/tasks/{no}/release")
    public TaskRow release(Actor actor, @PathVariable("no") String no, HttpServletRequest req) {
        return taskRow(actor, tasks.release(actor, no), req);
    }

    /**
     * 完成任务。审批任务 = 对它所在的审批单作决定（与 approvals/{no}/decide 同一套校验，409 → 403 → 422）；
     * 办理任务不在这里完成 —— 办理人走一条迁移，任务随状态离开自动完成（21/02 §8.2）。
     */
    @PostMapping("/tasks/{no}/complete")
    public ResponseEntity<Void> complete(Actor actor, @PathVariable("no") String no, @RequestBody(required = false) CompleteBody body) {
        TaskView t = tasks.get(actor, no);
        if (!TaskAppService.APPROVAL.equals(t.kind())) {
            throw new OpsKitException(COMPLETE_BY_TRANSITION, "work tasks complete by taking a transition on the instance");
        }
        String decision = body == null ? null : body.decision();
        approvals.decide(actor, t.approvalNo(), no, ApprovalController.approve(decision), body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    private TaskRow taskRow(Actor actor, TaskView t, HttpServletRequest req) {
        People people = new People(directory, actor.tenant(), java.util.Collections.singletonList(t.assignee()));
        return new TaskRow(t.taskNo(), t.kind(), t.status(), queue(actor, t.queueGroup(), Present.locale(req), new HashMap<>()), t.title(), null,
                t.priorityOrd(), t.createdAtMs(), t.instanceNo(), t.approvalNo(), "CLAIMED".equals(t.status()) ? people.of(t.assignee()) : null,
                t.claimExpiresAtMs());
    }

    private Named queue(Actor actor, String code, String locale, Map<String, Named> cache) {
        if (code == null) {
            return null;
        }
        return cache.computeIfAbsent(code, c -> directory.findGroup(actor.tenant(), c)
                .filter(g -> !g.name().isEmpty())
                .map(g -> Present.named(c, new ai.neargo.opskit.commons.i18n.LocalizedText(g.name()), locale))
                .orElse(new Named(c, c, Map.of())));
    }

    static OpsKitException invalid(String path, String error) {
        return new OpsKitException(CommonErrors.VALIDATION, "invalid " + path, Map.of(), List.of(new FieldError(path, error)));
    }

    static boolean notBlank(String s) {
        return Objects.nonNull(s) && !s.isBlank();
    }
}
