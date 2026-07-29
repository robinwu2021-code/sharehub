// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
import * as db from "../../mock/db";
import type { AlarmApi } from "../contracts/alarm";
import type { PageQ, AlarmQ } from "../query";
import { wait } from "./_wait";

export const alarmMock: AlarmApi = {
  listAlarmRecords: (q: AlarmQ = {}) => wait(db.listAlarmRecords(q)),
  listAlarmNotices: (q: PageQ = {}) => wait(db.listAlarmNotices(q)),
  listAlarmCodes: (q: PageQ = {}) => wait(db.listAlarmCodes(q)),
  listAlarmRules: (q: PageQ = {}) => wait(db.listAlarmRules(q)),
  saveAlarmCode: (x) => wait(db.saveAlarmCode(x), 350),
  saveAlarmRule: (x) => wait(db.saveAlarmRule(x), 350),
  raiseAlarmWorkOrder: (no) => wait(db.raiseAlarmWorkOrder(no), 400),
};
