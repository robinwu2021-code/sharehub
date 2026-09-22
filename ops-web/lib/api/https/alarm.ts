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
  // 备注可选：后端 @RequestBody(required=false) 收 Map，空对象即「不改备注」
  // ⚠️ 后端缺口：联动端点不存在（AlarmController 只有逐条 /work-order）。后端补齐时
  //    必须沿用 alarmNo 幂等键，且应由规则引擎在告警产生时触发，而非依赖运营手点。
  autoRaiseWorkOrders: () => client.post("/api/ops/alarms/auto-work-orders"),
  ackAlarm: (no, remark) => client.post(`/api/ops/alarms/records/${no}/ack`, remark ? { remark } : {}),
  // ⚠️ 后端缺口：AlarmController 的 /notices 是 append 表、只读，**没有任何写端点**（无 /resend）。
  // 落地时后端**必须自己按 idempotencyKey 去重**：前端这把键只是礼貌，挡不住刷新重放、双标签页与
  // 网关重试 —— 真发短信/邮件的动作，去重责任在服务端。键走 body 而非 Idempotency-Key 头：
  // 与发送记录重发一致（http-client 不支持自定义头，且键要落库可查）。
  resendAlarmNotice: (no, x) => client.post(`/api/ops/alarms/notices/${no}/resend`, x),

  // G1 软删除：归档 / 恢复。REST 上是「状态迁移」而非 DELETE —— 后端不得实现物理删除。
  archiveAlarmCode: (code) => client.post(`/api/ops/alarms/codes/${code}/archive`, {}),
  unarchiveAlarmCode: (code) => client.post(`/api/ops/alarms/codes/${code}/unarchive`, {}),
  archiveAlarmRule: (no) => client.post(`/api/ops/alarms/rules/${no}/archive`, {}),
  unarchiveAlarmRule: (no) => client.post(`/api/ops/alarms/rules/${no}/unarchive`, {}),
};
