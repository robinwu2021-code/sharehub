package ai.neargo.sharehub.portal.ops;

import ai.neargo.common.core.PageResult;
import org.springframework.web.bind.annotation.RequestBody;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AckResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmCode;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmNotice;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRecord;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRule;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.WorkOrderRef;
import ai.neargo.sharehub.alarm.entity.DevAlarmCode;
import ai.neargo.sharehub.alarm.entity.DevAlarmRule;
import ai.neargo.sharehub.alarm.service.AlarmCodeService;
import ai.neargo.sharehub.alarm.service.AlarmRuleService;
import ai.neargo.sharehub.alarm.service.AlarmService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 告警管理（[api/README §3.3]，菜单 4 叶：告警记录 / 告警通知 / 告警代码 / 通知规则）。
 *
 * <p>路径前缀 {@code /api/ops/alarms}，与 {@link OpsController} 的 {@code /api/ops} 同前缀但
 * **子路径不重叠**（那边是 dashboard/cabinets/work-orders/sites/…）。新增端点前须核对已占用路径，
 * 重复映射会让 Spring 启动即失败。
 *
 * <p>职责只有路由 + 鉴权 + 调 service：不写业务、不碰 mapper。响应包由全局 wrapper 自动加。
 * 写操作形态遵循 [api/README §1.5]：{@code POST /{collection}} 建、{@code POST /{collection}/{no}} 改，
 * **全站无 DELETE**。
 */
@RestController
@RequestMapping("/api/ops/alarms")
public class AlarmController {

    private final AlarmService alarmService;
    private final AlarmCodeService codeService;
    private final AlarmRuleService ruleService;

    public AlarmController(AlarmService alarmService, AlarmCodeService codeService,
                           AlarmRuleService ruleService) {
        this.alarmService = alarmService;
        this.codeService = codeService;
        this.ruleService = ruleService;
    }

    // —— 告警记录（菜单叶：告警管理 › 告警记录）——

    @GetMapping("/records")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<AlarmRecord> records(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String level,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String cabinetNo) {
        return alarmService.page(page, size, keyword, level, status, cabinetNo);
    }

    /** 确认告警（OPEN → ACKED）。可带处置备注。 */
    @PostMapping("/records/{alarmNo}/ack")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public AckResult ack(@PathVariable String alarmNo,
                         @RequestBody(required = false) Map<String, Object> body) {
        Object remark = body == null ? null : body.get("remark");
        return alarmService.ack(alarmNo, remark == null ? null : String.valueOf(remark));
    }

    /**
     * 一键转工单，**以 {@code alarmNo} 为幂等键**（[api/README §3.3]）。
     *
     * <p>重复调用返回首次生成的 {@code woNo}（结果里 {@code created=false}），不产生第二张单。
     * 权限用 {@code workorder:wo:create} 而非 {@code :read} —— 这一下是真的建了张工单。
     */
    @PostMapping("/records/{alarmNo}/work-order")
    @PreAuthorize("@perm.can('workorder:wo:create')")
    public WorkOrderRef toWorkOrder(@PathVariable String alarmNo) {
        return alarmService.toWorkOrder(alarmNo);
    }

    // —— 告警通知流水（菜单叶：告警通知）。append 表，只读，无写端点。——

    @GetMapping("/notices")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<AlarmNotice> notices(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String alarmNo,
                                           @RequestParam(required = false) String channel,
                                           @RequestParam(required = false) String status) {
        return alarmService.pageNotices(page, size, keyword, alarmNo, channel, status);
    }

    // —— 告警代码字典（菜单叶：告警代码）——

    @GetMapping("/codes")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<AlarmCode> codes(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(required = false) String autoWorkOrder) {
        return codeService.page(page, size, keyword,
                Map.of("level", nz(level), "autoWorkOrder", nz(autoWorkOrder)));
    }

    @PostMapping("/codes")
    // 清单 §告警「告警代码 / 通知规则 配置」的码是 workorder:alarm:config，
    // 此前挂通用的 wo:update —— 配置告警与处理工单是两件事。（OPS 持 workorder:* 通配，访问面不变。）
    @PreAuthorize("@perm.can('workorder:alarm:config')")
    public AlarmCode createCode(@RequestBody DevAlarmCode body) {
        return codeService.save(body);
    }

    @PostMapping("/codes/{code}")
    @PreAuthorize("@perm.can('workorder:alarm:config')")
    public AlarmCode updateCode(@PathVariable String code, @RequestBody DevAlarmCode body) {
        body.setCode(code); // 路径为准，忽略 body 里的键，防越权改他码
        return codeService.save(body);
    }

    // —— 通知规则（菜单叶：通知规则）——

    @GetMapping("/rules")
    @PreAuthorize("@perm.can('workorder:wo:read')")
    public PageResult<AlarmRule> rules(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String alarmCode,
                                       @RequestParam(required = false) String channel,
                                       @RequestParam(required = false) String method,
                                       @RequestParam(required = false) String status) {
        return ruleService.page(page, size, keyword,
                Map.of("alarmCode", nz(alarmCode), "channel", nz(channel),
                        "method", nz(method), "status", nz(status)));
    }

    @PostMapping("/rules")
    @PreAuthorize("@perm.can('workorder:alarm:config')")
    public AlarmRule createRule(@RequestBody DevAlarmRule body) {
        return ruleService.save(body);
    }

    @PostMapping("/rules/{ruleNo}")
    @PreAuthorize("@perm.can('workorder:alarm:config')")
    public AlarmRule updateRule(@PathVariable String ruleNo, @RequestBody DevAlarmRule body) {
        body.setRuleNo(ruleNo);
        return ruleService.save(body);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档AlarmCode。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/codes/{no}/archive")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object archiveAlarmCode(@PathVariable String no) {
        return codeService.archive(no);
    }

    /** 取消归档AlarmCode：清空时间戳，回到默认列表。 */
    @PostMapping("/codes/{no}/unarchive")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object unarchiveAlarmCode(@PathVariable String no) {
        return codeService.unarchive(no);
    }

    /**
     * 归档AlarmRule。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/rules/{no}/archive")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object archiveAlarmRule(@PathVariable String no) {
        return ruleService.archive(no);
    }

    /** 取消归档AlarmRule：清空时间戳，回到默认列表。 */
    @PostMapping("/rules/{no}/unarchive")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object unarchiveAlarmRule(@PathVariable String no) {
        return ruleService.unarchive(no);
    }

    /**
     * 未处理告警批量自动开单。**幂等**：已有工单的告警跳过，运营点两次不产生两张单。
     *
     * @return 本次新开的工单数
     */
    @PostMapping("/auto-work-orders")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object autoRaiseWorkOrders() {
        return java.util.Map.of("raised", alarmService.autoRaiseWorkOrders());
    }

    /** 重发告警通知。**必带幂等键** —— 通知是真发真扣钱，双击不该发两条。 */
    @PostMapping("/notices/{noticeNo}/resend")
    @PreAuthorize("@perm.can('workorder:alarm:update')")
    public Object resendAlarmNotice(@PathVariable String noticeNo,
                                    @RequestBody(required = false) java.util.Map<String, Object> body) {
        Object k = body == null ? null : body.get("idempotencyKey");
        return alarmService.resendNotice(noticeNo, k == null ? null : String.valueOf(k));
    }
}
