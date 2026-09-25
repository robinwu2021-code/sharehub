// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import type { PageQ, AlarmQ, ArchiveQ } from "../query";
import type {
  PageResult, AlarmRecord, AlarmNotice, AlarmCode, AlarmRule, AlarmAckResult, AlarmWorkOrderRef,
  AlarmNoticeResendPayload, AutoWorkOrderResult, AlarmCloseReason,
  AlarmSummary, DispositionPreview, AlarmRoute, AlarmRouteReq, AlarmCodeStat, AlarmTodo,
} from "../../types";

export interface AlarmApi {
  listAlarmRecords(q?: AlarmQ): Promise<PageResult<AlarmRecord>>;
  listAlarmNotices(q?: PageQ): Promise<PageResult<AlarmNotice>>;
  listAlarmCodes(q?: ArchiveQ): Promise<PageResult<AlarmCode>>;
  listAlarmRules(q?: ArchiveQ): Promise<PageResult<AlarmRule>>;
  saveAlarmCode(x: Partial<AlarmCode> & { code?: string }): Promise<AlarmCode>;
  saveAlarmRule(x: Partial<AlarmRule> & { ruleNo?: string }): Promise<AlarmRule>;
  /**
   * 告警转工单：生成关联工单号并置为已受理。
   * **以 alarmNo 为幂等键** —— 重复调用返回首次的 woNo 且 `created=false`，不产生第二张单。
   * 返回 WorkOrderRef 而非整行告警（后端 AlarmController#toWorkOrder），故调用方要靠 invalidate 刷列表。
   */
  raiseAlarmWorkOrder(alarmNo: string): Promise<AlarmWorkOrderRef>;
  /**
   * 确认告警（OPEN → ACKED）：不开工单、只认领处置责任 —— 误报/自愈类告警不该被迫开单才能消掉。
   * 只回状态而非整行，故调用方要靠 invalidate 刷列表。remark 可选，非空才覆盖原备注。
   */
  ackAlarm(alarmNo: string, remark?: string): Promise<AlarmAckResult>;
  /**
   * 关闭告警。**原因必填** —— 「误报率」这个数只有在关闭时记了原因才算得出来。
   * 与受理是两个权限码：受理是「在处理」，关闭会把它从未处理里抹掉。
   */
  closeAlarm(alarmNo: string, reason: AlarmCloseReason, note?: string): Promise<AlarmAckResult>;
  /**
   * 告警 → 自动开工单联动：把「告警码字典的 autoWorkOrder 开关」真正接上转工单端点。
   * **天然幂等**（逐条复用转工单的 alarmNo 幂等键），重复触发只增 skipped，不会重复开单。
   */
  autoRaiseWorkOrders(): Promise<AutoWorkOrderResult>;
  /**
   * 重发告警通知（拍板 #6）：**新增**一条流水并返回它，原记录不动（审计要看得见发了两次）。
   * `idempotencyKey` 必带且**服务端按键去重**；只允许重发 FAILED 的通知，
   * 目标命中触达拉黑、或告警已关闭时拒绝。返回新流水，故调用方仍要 invalidate 刷列表。
   */
  resendAlarmNotice(noticeNo: string, x: AlarmNoticeResendPayload): Promise<AlarmNotice>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveAlarmCode(code: string): Promise<AlarmCode>;
  unarchiveAlarmCode(code: string): Promise<AlarmCode>;
  archiveAlarmRule(ruleNo: string): Promise<AlarmRule>;
  unarchiveAlarmRule(ruleNo: string): Promise<AlarmRule>;

  // ——— 业务告警与待办（2026-09-25 裁决 #3：设备错误码降为信号，告警中心只放业务告警）———

  /** 摘要：按域计数 + 已处置未关闭 + 今日自愈。 */
  alarmSummary(): Promise<AlarmSummary>;
  /** 单条告警详情。 */
  getAlarmRecord(alarmNo: string): Promise<AlarmRecord>;
  /**
   * 处置预览：**点下去之前先让人看见会发生什么** ——
   * 开哪类工单、派给谁、会不会并进已有的单。
   * 不给预览的话，运营点完才知道系统把单派给了错的人，而工单已经开出去了。
   */
  alarmDispositionPreview(alarmNo: string): Promise<DispositionPreview>;
  /** 执行处置（按路由规则开单 / 转客服 / 自愈 / 通知）。 */
  disposeAlarm(alarmNo: string): Promise<Record<string, unknown>>;

  /** 告警码的处置路由。 */
  listAlarmRoutes(code: string): Promise<AlarmRoute[]>;
  /** 保存该码的路由（整组覆盖）。 */
  saveAlarmRoutes(code: string, routes: AlarmRouteReq[]): Promise<AlarmRoute[]>;
  /** 每码统计：误报率 / 自愈率 / 撤单率——调规则的依据。 */
  alarmCodeStats(): Promise<AlarmCodeStat[]>;

  /** 待办列表。`mine=true` 只看该我办的。 */
  listAlarmTodos(q?: { mine?: boolean; page?: number; size?: number }): Promise<PageResult<AlarmTodo>>;
  /** 未完成待办数（看板红点）。 */
  alarmTodoCount(): Promise<Record<string, number>>;
  /** 办结一条待办。 */
  doneAlarmTodo(todoNo: string, note?: string): Promise<AlarmTodo>;
}
