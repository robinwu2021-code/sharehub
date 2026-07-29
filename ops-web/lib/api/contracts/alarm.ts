// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import type { PageQ, AlarmQ, ArchiveQ } from "../query";
import type { PageResult, AlarmRecord, AlarmNotice, AlarmCode, AlarmRule } from "../../types";

export interface AlarmApi {
  listAlarmRecords(q?: AlarmQ): Promise<PageResult<AlarmRecord>>;
  listAlarmNotices(q?: PageQ): Promise<PageResult<AlarmNotice>>;
  listAlarmCodes(q?: ArchiveQ): Promise<PageResult<AlarmCode>>;
  listAlarmRules(q?: ArchiveQ): Promise<PageResult<AlarmRule>>;
  saveAlarmCode(x: Partial<AlarmCode> & { code?: string }): Promise<AlarmCode>;
  saveAlarmRule(x: Partial<AlarmRule> & { ruleNo?: string }): Promise<AlarmRule>;
  /** 告警转工单：生成关联工单号并置为已受理，返回更新后的告警记录。 */
  raiseAlarmWorkOrder(alarmNo: string): Promise<AlarmRecord>;

  // === G1 软删除（TDD §10.1）：归档而非删除，**契约里禁止出现 deleteXxx** ===
  archiveAlarmCode(code: string): Promise<AlarmCode>;
  unarchiveAlarmCode(code: string): Promise<AlarmCode>;
  archiveAlarmRule(ruleNo: string): Promise<AlarmRule>;
  unarchiveAlarmRule(ruleNo: string): Promise<AlarmRule>;
}
