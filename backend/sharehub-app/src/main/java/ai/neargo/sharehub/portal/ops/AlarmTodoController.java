package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmTodo;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.NoteReq;
import ai.neargo.sharehub.alarm.todo.AlarmTodoService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 告警待办（经营看板 › 待办中心）：合作 / 经营类告警的处置落点（TDD/05 §7.4）。 */
@RestController
@RequestMapping("/api/ops/alarm-todos")
public class AlarmTodoController {

    private final AlarmTodoService todos;

    public AlarmTodoController(AlarmTodoService todos) {
        this.todos = todos;
    }

    @GetMapping
    @PreAuthorize("@perm.can('dashboard:todo:read')")
    public PageResult<AlarmTodo> page(@RequestParam(required = false, defaultValue = "true") boolean mine,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return todos.page(mine, status, page, size);
    }

    @GetMapping("/count")
    @PreAuthorize("@perm.can('dashboard:todo:read')")
    public Map<String, Long> openCount() {
        return Map.of("open", todos.openCountForMe());
    }

    /** 完成待办：告警条件已不成立 → 告警关闭；仍成立 → 下一轮重新生成待办。 */
    @PostMapping("/{todoNo}/done")
    @PreAuthorize("@perm.can('dashboard:todo:read')")
    public AlarmTodo done(@PathVariable String todoNo, @RequestBody(required = false) NoteReq body) {
        return todos.done(todoNo, body == null ? null : body.note());
    }
}
