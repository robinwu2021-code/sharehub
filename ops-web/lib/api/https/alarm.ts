// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
// 端点前缀：/api/alarm/**
import { client } from "../http-client";
import type { AlarmApi } from "../contracts/alarm";
import type { PageQ, AlarmQ } from "../query";

export const alarmHttp: AlarmApi = {
  listAlarmRecords: (q?: AlarmQ) => client.get("/api/alarm/records", q),
  listAlarmNotices: (q?: PageQ) => client.get("/api/alarm/notices", q),
  listAlarmCodes: (q?: PageQ) => client.get("/api/alarm/codes", q),
  listAlarmRules: (q?: PageQ) => client.get("/api/alarm/rules", q),
  saveAlarmCode: (x) => client.post(x.code ? `/api/alarm/codes/${x.code}` : "/api/alarm/codes", x),
  saveAlarmRule: (x) => client.post(x.ruleNo ? `/api/alarm/rules/${x.ruleNo}` : "/api/alarm/rules", x),
  raiseAlarmWorkOrder: (no) => client.post(`/api/alarm/records/${no}/work-order`, {}),
};
