package ai.neargo.sharehub.alarm.todo;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.common.core.ServerException;
import ai.neargo.sharehub.alarm.AlarmTodoStatus;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmTodo;
import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmTodo;
import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.TodoMapper;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 告警待办实现。「我的」= 待办承接角色是我的角色，或具体承接人是我。 */
@Service
public class AlarmTodoServiceImpl implements AlarmTodoService {

    private final TodoMapper todos;
    /** 延迟取：引擎也依赖本服务（建待办），直接注入会成环。 */
    private final ObjectProvider<AlarmEngine> engine;

    public AlarmTodoServiceImpl(TodoMapper todos, ObjectProvider<AlarmEngine> engine) {
        this.todos = todos;
        this.engine = engine;
    }

    @Override
    @Transactional
    public AlarmTodo create(DevAlarm a, String roleCode) {
        DevAlarmTodo open = openOf(a.getAlarmNo());
        if (open != null) return toVO(open, a.getAlarmCode());
        DevAlarmTodo t = new DevAlarmTodo();
        t.setTodoNo(IdGenerator.next(BizKey.ALARM_TODO));
        t.setTenantId("MAIN");
        t.setAlarmNo(a.getAlarmNo());
        t.setRoleCode(roleCode == null ? "OPS" : roleCode);
        t.setTitle(titleOf(a));
        t.setStatus(AlarmTodoStatus.OPEN.name());
        t.setSiteNo(a.getSiteNo());
        t.setAgentNo(a.getAgentNo());
        try {
            todos.insert(t);
        } catch (DuplicateKeyException race) {
            return toVO(openOf(a.getAlarmNo()), a.getAlarmCode());
        }
        return toVO(t, a.getAlarmCode());
    }

    private static String titleOf(DevAlarm a) {
        String obj = a.getSiteNo() != null ? "站点 " + a.getSiteNo() : a.getSubjectNo();
        return switch (a.getAlarmCode()) {
            case "SITE_WITHOUT_CONTRACT" -> obj + " 在营业但没有生效合同：请续签 / 补签，或发起撤场";
            default -> a.getAlarmCode() + " · " + obj;
        };
    }

    @Override
    public PageResult<AlarmTodo> page(boolean mine, String status, Integer page, Integer size) {
        LambdaQueryWrapper<DevAlarmTodo> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(DevAlarmTodo::getStatus, AlarmTodoStatus.of(status).name());
        if (mine) mineOf(w);
        w.orderByDesc(DevAlarmTodo::getId);
        int p = page == null || page < 1 ? 1 : page, s = size == null || size < 1 ? 10 : Math.min(size, 200);
        Page<DevAlarmTodo> r = todos.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(t -> toVO(t, null)).toList(), r.getTotal());
    }

    private static void mineOf(LambdaQueryWrapper<DevAlarmTodo> w) {
        LoginUser u = SecurityUtils.currentUser().orElse(null);
        if (u == null) {
            w.apply("1 = 0");
            return;
        }
        w.and(x -> x.eq(DevAlarmTodo::getAssigneeNo, u.userNo()).or().eq(u.role() != null, DevAlarmTodo::getRoleCode, u.role()));
    }

    @Override
    @Transactional
    public AlarmTodo done(String todoNo, String note) {
        DevAlarmTodo t = require(todoNo);
        if (!AlarmTodoStatus.OPEN.name().equals(t.getStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.todo.done");
        String me = SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
        int n = todos.update(null, new LambdaUpdateWrapper<DevAlarmTodo>().eq(DevAlarmTodo::getId, t.getId())
                .eq(DevAlarmTodo::getStatus, AlarmTodoStatus.OPEN.name()).set(DevAlarmTodo::getStatus, AlarmTodoStatus.DONE.name())
                .set(DevAlarmTodo::getDoneAt, LocalDateTime.now()).set(DevAlarmTodo::getDoneBy, me)
                .set(DevAlarmTodo::getDoneNote, note));
        if (n == 0) throw ai.neargo.sharehub.common.BizException.conflict("error.todo.done");
        engine.getObject().onDispositionDone("TODO", todoNo);
        return toVO(require(todoNo), null);
    }

    @Override
    @Transactional
    public void cancel(String todoNo, String reason) {
        todos.update(null, new LambdaUpdateWrapper<DevAlarmTodo>().eq(DevAlarmTodo::getTodoNo, todoNo)
                .eq(DevAlarmTodo::getStatus, AlarmTodoStatus.OPEN.name()).set(DevAlarmTodo::getStatus, AlarmTodoStatus.CANCELLED.name())
                .set(DevAlarmTodo::getDoneAt, LocalDateTime.now()).set(DevAlarmTodo::getDoneBy, "SYSTEM").set(DevAlarmTodo::getDoneNote, reason));
    }

    @Override
    public long openCountForMe() {
        LambdaQueryWrapper<DevAlarmTodo> w = new LambdaQueryWrapper<DevAlarmTodo>().eq(DevAlarmTodo::getStatus, AlarmTodoStatus.OPEN.name());
        mineOf(w);
        return todos.selectCount(w);
    }

    private DevAlarmTodo openOf(String alarmNo) {
        return todos.selectOne(new LambdaQueryWrapper<DevAlarmTodo>().eq(DevAlarmTodo::getAlarmNo, alarmNo)
                .eq(DevAlarmTodo::getStatus, AlarmTodoStatus.OPEN.name()).last("limit 1"));
    }

    private DevAlarmTodo require(String todoNo) {
        DevAlarmTodo t = todos.selectOne(new LambdaQueryWrapper<DevAlarmTodo>().eq(DevAlarmTodo::getTodoNo, todoNo).last("limit 1"));
        if (t == null) throw BizException.notFound(todoNo);
        return t;
    }

    private static AlarmTodo toVO(DevAlarmTodo t, String code) {
        return new AlarmTodo(t.getTodoNo(), t.getAlarmNo(), code, t.getRoleCode(), t.getAssigneeNo(), t.getTitle(), t.getStatus(),
                t.getSiteNo(), t.getCreatedAt(), t.getDoneAt(), t.getDoneBy(), t.getDoneNote());
    }
}
