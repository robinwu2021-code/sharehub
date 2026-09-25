// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import * as db from "../../mock/db";
// 重发尚未汇出到 mock/db/index.ts（该文件由主干集中合并），故直接引子模块（同 lib/api/mocks/cs.ts 的做法）。
import { resendAlarmNotice } from "../../mock/db/alarm";
import type { AlarmApi } from "../contracts/alarm";
import type { PageQ, AlarmQ, ArchiveQ } from "../query";
import { wait } from "./_wait";

export const alarmMock: AlarmApi = {
  listAlarmRecords: (q: AlarmQ = {}) => wait(db.listAlarmRecords(q)),
  listAlarmNotices: (q: PageQ = {}) => wait(db.listAlarmNotices(q)),
  listAlarmCodes: (q: ArchiveQ = {}) => wait(db.listAlarmCodes(q)),
  listAlarmRules: (q: ArchiveQ = {}) => wait(db.listAlarmRules(q)),
  saveAlarmCode: (x) => wait(db.saveAlarmCode(x), 350),
  saveAlarmRule: (x) => wait(db.saveAlarmRule(x), 350),
  raiseAlarmWorkOrder: (no) => wait(db.raiseAlarmWorkOrder(no), 400),
  autoRaiseWorkOrders: () => wait(db.autoRaiseWorkOrders(), 500),
  ackAlarm: (no, remark) => wait(db.ackAlarm(no, remark), 350),
  closeAlarm: (alarmNo, reason, note) => wait(db.closeAlarm(alarmNo, reason, note)),
  resendAlarmNotice: (no, x) => wait(resendAlarmNotice(no, x), 400),

  // G1 软删除：归档 / 恢复（禁止物理删除）
  archiveAlarmCode: async (code) => wait(db.archiveAlarmCode(code), 350),
  unarchiveAlarmCode: async (code) => wait(db.unarchiveAlarmCode(code), 350),
  archiveAlarmRule: async (no) => wait(db.archiveAlarmRule(no), 350),
  unarchiveAlarmRule: async (no) => wait(db.unarchiveAlarmRule(no), 350),
};
