// 工单增强：摘要条 · 详情（时间线 + 照片 + 关联告警）· 派单候选人 · 派生与接管。
//
// ⚠️ **为什么单独成文件而不是并进 workorder.ts**
// 2026-09-25 落这一批时，`lib/types/workorder.ts` 正被另一个会话改着（`git status` 长时间为 `M`）。
// 按 CLAUDE.md 的并发纪律「有在途改动则改用非侵入方式」——
// 往那个文件里加内容，`git add` 会把对方未完成的改动一起提交。
// **这不是永久归宿**：等 `workorder.ts` 空出来应当并回去、本文件删除。
// 同款处理见 `lib/types/device-ops.ts` 与 `lib/types/alarm-biz.ts`。

import type { WorkOrder } from "./workorder";
import type { FileRef } from "./file";
import type { AlarmRecord } from "./alarm";

/**
 * 工单摘要条（后端 `WoSummary`）。
 *
 * <p>四个数**都是要人动手的事**，而且按紧急度排：
 * 待派单 → 即将超时 → 已超时 → 验收不通过。
 * 不放「工单总数」——它不会让任何人去做任何事。
 *
 * <p>`overdue` 能有数，靠的是 `wo-sla-breach-scan` 定时扫
 * （事件驱动那半只在接单/关单时判，**没人管的单永远不会被标超时**，
 * 而那恰恰是最该出现在这一格里的）。
 */
export interface WoSummary {
  toDispatch: number;
  dueSoon: number;
  overdue: number;
  reviewFailed: number;
}

/**
 * 派单候选人（后端 `AssigneeCandidate`）。
 *
 * <p>`siteOwner` 标出**这个站点的运维责任人** —— 派单时把他排在最前面。
 * 此前派单抽屉的候选人是写死的 `STAFF` 常量，
 * 于是运营得自己记住「哪个站归谁」，记错了单子就派给了另一个城市的人。
 */
export interface AssigneeCandidate {
  /** `EMPLOYEE` / `AGENT`。 */
  type: string;
  no: string;
  name: string;
  siteOwner: boolean;
}

/**
 * 工单时间线的一条（后端 `TimelineItem`）。
 *
 * <p>`kind` 区分这条来自哪一步（派单 / 接单 / 处理 / 完工 / 验收…），
 * `fileNos` 是该步骤留下的照片 —— 照片挂在**步骤**上而不是工单上，
 * 否则「这张图是修之前还是修之后拍的」就永远说不清。
 */
export interface WoTimelineItem {
  kind: string;
  action: string | null;
  actor: string | null;
  note: string | null;
  /** 故障原因码。完工时必填，是统计「这类故障占比」的唯一依据。 */
  faultReasonCode: string | null;
  fileNos: string[];
  at: string;
}

/**
 * 工单详情（后端 `WorkOrderDetailView`）。
 *
 * <p>**关联告警是这里最要紧的一块**：一张维修单可能同时压着好几条告警，
 * 修完不看告警就关单的话，那几条告警会继续躺在告警中心，
 * 而现场其实已经好了 —— 告警数长期虚高，最后没人再信它。
 */
export interface WorkOrderDetail {
  order: WorkOrder;
  timeline: WoTimelineItem[];
  photos: FileRef[];
  alarms: AlarmRecord[];
}

/** 派生子单（现场发现的新问题另开一张，而不是塞进当前单的备注里）。 */
export interface WoDeriveReq {
  type: string;
  priority?: string | null;
  cabinetNo?: string | null;
  description: string;
}

/** 接管：把单子转给另一个人，原因必填（留痕给被接管的人看）。 */
export interface WoTakeoverReq {
  employeeNo: string;
  reason: string;
}
