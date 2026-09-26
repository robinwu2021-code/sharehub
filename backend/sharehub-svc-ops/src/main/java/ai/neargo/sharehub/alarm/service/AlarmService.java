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
     * 关闭告警：{@code OPEN / ACKED → CLOSED}，非法迁移抛异常。
     *
     * <p><b>这条边此前有定义、没人走</b>：{@code AlarmStateMachine} 里两条 CLOSE 边俱全，
     * 而主源码中没有任何一处发这个事件 —— 告警只能 {@code OPEN → ACKED} 然后停住，
     * {@code dev_alarm} 的 ACKED 行只增不减，「还有多少没处理」这个数从此说不清。
     *
     * @param reason 关闭原因，**必填**（见 {@link ai.neargo.sharehub.alarm.AlarmCloseReason}）
     * @param note   备注，可空
     */
    AckResult close(String alarmNo, String reason, String note);

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

    // ——————————— 2026-09-25 业务告警（TDD-运营核心流程/05 §九）———————————

    /** 列表筛选（在旧参数之上追加业务维度）。{@code topOnly} = 只看未被取代的（parent 为空）。 */
    /**
     * @param preset 摘要卡对应的预置子集：{@code DISPOSED_OPEN}（已处置未关闭）/ {@code HEALED_TODAY}（今日自愈）。
     *               <p><b>为什么做成 preset 而不是让调用方拼条件</b>：这两个子集各有两三个条件
     *               （前者「未关闭 + 有处置单」，后者「已关闭 + 关闭原因是自愈/自动修复 + 今天关的」）。
     *               调用方自己拼的话，摘要卡的数字与点进去的列表条数迟早对不上 ——
     *               而那比「卡片不可点」更糟：两个数都摆在界面上，人只会以为数据错了。
     *               服务端用同一段条件同时算摘要与列表，见 {@code AlarmServiceImpl#applyPreset}。
     */
    record AlarmQuery(Integer page, Integer size, String keyword, String level, String status, String cabinetNo,
                      String domain, String subjectType, String siteNo, String cause, String disposition,
                      java.time.LocalDate from, java.time.LocalDate to, Boolean topOnly, String preset) {
    }

    PageResult<AlarmRecord> page(AlarmQuery q);

    ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmDetail detail(String alarmNo);

    ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmSummary summary();

    java.util.List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute> routes(String code);

    /** 整体替换该码的路由（内置码的路由也允许调：路由是运营策略，不是码的身份）。 */
    java.util.List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute> saveRoutes(String code,
            java.util.List<ai.neargo.sharehub.alarm.dto.AlarmDtos.RouteReq> routes);

    java.util.List<ai.neargo.sharehub.alarm.dto.AlarmDtos.CodeStat> codeStats(int days);

    /** 以该工单为处置的告警（工单详情「关联告警」）。 */
    java.util.List<AlarmRecord> byWorkOrder(String woNo);

    /** 每张工单关联的告警数（工单列表）。 */
    java.util.Map<String, Integer> countByWorkOrders(java.util.Collection<String> woNos);
}
