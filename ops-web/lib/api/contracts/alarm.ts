// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import type { PageQ, AlarmQ } from "../query";
import type { PageResult, AlarmRecord, AlarmNotice, AlarmCode, AlarmRule } from "../../types";

export interface AlarmApi {
  listAlarmRecords(q?: AlarmQ): Promise<PageResult<AlarmRecord>>;
  listAlarmNotices(q?: PageQ): Promise<PageResult<AlarmNotice>>;
  listAlarmCodes(q?: PageQ): Promise<PageResult<AlarmCode>>;
  listAlarmRules(q?: PageQ): Promise<PageResult<AlarmRule>>;
  saveAlarmCode(x: Partial<AlarmCode> & { code?: string }): Promise<AlarmCode>;
  saveAlarmRule(x: Partial<AlarmRule> & { ruleNo?: string }): Promise<AlarmRule>;
  /** 告警转工单：生成关联工单号并置为已受理，返回更新后的告警记录。 */
  raiseAlarmWorkOrder(alarmNo: string): Promise<AlarmRecord>;
}
