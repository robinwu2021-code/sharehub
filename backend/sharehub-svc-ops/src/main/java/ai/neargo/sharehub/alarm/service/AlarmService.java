package ai.neargo.sharehub.alarm.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AckResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmNotice;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRecord;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.WorkOrderRef;

/**
 * 告警记录业务（聚合根，**有业务规则 → 手写，不继承 CrudService**）。
 *
 * <p>三件事：查（带数据权限冗余列的筛选）、确认（状态机）、转工单（幂等）。
 */
public interface AlarmService {

    /** 告警记录分页；筛选维度对齐 [api/README §3.3]：{@code level/status/cabinetNo}。 */
    PageResult<AlarmRecord> page(Integer page, Integer size, String keyword,
                                 String level, String status, String cabinetNo);

    /** 通知流水分页（append 表，只读）。 */
    PageResult<AlarmNotice> pageNotices(Integer page, Integer size, String keyword,
                                        String alarmNo, String channel, String status);

    /** 确认告警：{@code OPEN → ACKED}，非法迁移抛异常。 */
    AckResult ack(String alarmNo, String remark);

    /**
     * 一键转工单，**以 {@code alarmNo} 为幂等键**。
     *
     * <p>重复调用返回首次生成的 {@code woNo}，不产生第二张单
     * （[api/README §3.3] / [db-design §1.6]，硬约束是 {@code wo_order.source_ref} 的 UNIQUE）。
     */
    WorkOrderRef toWorkOrder(String alarmNo);

    /**
     * 未处理告警批量自动开单。
     *
     * <p><b>幂等</b>：已有 {@code wo_no} 的告警跳过，不重复开单 ——
     * 运营点两次不该产生两张工单（与 {@code ComplaintService#toWorkOrder} 同一约定）。
     *
     * @return 本次新开的工单数
     */
    int autoRaiseWorkOrders();

    /** 重发告警通知。**必带幂等键** —— 通知是真发真扣钱，双击不该发两条。 */
    Object resendNotice(String noticeNo, String idempotencyKey);
}
