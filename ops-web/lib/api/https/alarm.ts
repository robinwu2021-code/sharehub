// 覆盖范围：告警治理 —— 告警记录 / 通知流水 / 告警代码字典 / 通知规则 / 告警转工单。
// 端点前缀：/api/ops/alarms/**
import { client } from "../http-client";
import type { AlarmApi } from "../contracts/alarm";
import type { PageQ, AlarmQ, ArchiveQ } from "../query";

export const alarmHttp: AlarmApi = {
  listAlarmRecords: (q?: AlarmQ) => client.get("/api/ops/alarms/records", q),
  listAlarmNotices: (q?: PageQ) => client.get("/api/ops/alarms/notices", q),
  listAlarmCodes: (q?: ArchiveQ) => client.get("/api/ops/alarms/codes", q),
  listAlarmRules: (q?: ArchiveQ) => client.get("/api/ops/alarms/rules", q),
  saveAlarmCode: (x) => client.post(x.code ? `/api/ops/alarms/codes/${x.code}` : "/api/ops/alarms/codes", x),
  saveAlarmRule: (x) => client.post(x.ruleNo ? `/api/ops/alarms/rules/${x.ruleNo}` : "/api/ops/alarms/rules", x),
  raiseAlarmWorkOrder: (no) => client.post(`/api/ops/alarms/records/${no}/work-order`, {}),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveAlarmCode: (code) => client.post(`/api/ops/alarms/codes/${code}/archive`, {}),
  unarchiveAlarmCode: (code) => client.post(`/api/ops/alarms/codes/${code}/unarchive`, {}),
  archiveAlarmRule: (no) => client.post(`/api/ops/alarms/rules/${no}/archive`, {}),
  unarchiveAlarmRule: (no) => client.post(`/api/ops/alarms/rules/${no}/unarchive`, {}),
};
