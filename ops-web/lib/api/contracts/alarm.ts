// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import type { PageQ, AlarmQ, ArchiveQ } from "../query";
import type {
  PageResult, AlarmRecord, AlarmNotice, AlarmCode, AlarmRule, AlarmAckResult, AlarmWorkOrderRef,
  AlarmNoticeResendPayload,

  AutoWorkOrderResult, AlarmCloseReason,
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
}
