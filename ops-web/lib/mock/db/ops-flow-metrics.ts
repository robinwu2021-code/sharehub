// 运营核心流程指标（mock）：镜像后端 OpsFlowMetricsService 的口径，从 mock db 现算。
//
// 只读统计，不写 db；但**必须从 db 现算而不是写死一组数** —— 写死的话，
// 在 mock 下完工一张工单、签下一条线索，看板纹丝不动，「指标跟着业务动」这件事就验不了。
// mock 里没有来源数据的几项（试借还、装机时效、撤场清点、签约周期）按后端口径返回「分母 0 → null」，
// 页面上显示「无数据」，而不是编一个数。
import type { OpsFlowMetrics, WorkOrder } from "../../types";
import { workOrders } from "./workorder";
import { contracts, leads } from "./location";

const DONE: WorkOrder["status"][] = ["DONE", "AUDITED", "CLOSED"];

/** 比率保留 4 位（与后端 `divide(…, 4, HALF_UP)` 同精度）；分母 0 → null。 */
const ratio = (part: number, whole: number) => (whole === 0 ? null : Math.round((part / whole) * 10000) / 10000);

const inWindow = (at: string | null | undefined, from: string, to: string) => {
  if (!at) return false;
  const d = at.slice(0, 10);
  return d >= from && d < to;
};

const ymd = (d: Date) => d.toISOString().slice(0, 10);

export function opsFlowMetrics(q: { from?: string; to?: string } = {}): OpsFlowMetrics {
  const to = q.to ?? ymd(new Date(Date.now() + 86400_000));
  const from = q.from ?? ymd(new Date(new Date(to).getTime() - 30 * 86400_000));

  // 工单 SLA：窗口内创建、已完工且有 SLA 计时的；按完工时刻是否超过 slaDueAt 判
  const slaRows = workOrders.filter((w) => DONE.includes(w.status) && w.slaDueAt && inWindow(w.createdAt, from, to));
  const finishedAt = (w: WorkOrder) => w.completedAt ?? w.handledAt ?? null;
  const slaOk = slaRows.filter((w) => {
    const f = finishedAt(w);
    return f !== null && new Date(f).getTime() <= new Date(w.slaDueAt!).getTime();
  }).length;

  // MTTR：已完工故障单，开单到完工的平均分钟
  const faults = workOrders.filter((w) => w.type === "FAULT" && DONE.includes(w.status) && inWindow(w.createdAt, from, to) && finishedAt(w));
  const mttr = faults.length
    ? Math.round(faults.reduce((n, w) => n + (new Date(finishedAt(w)!).getTime() - new Date(w.createdAt).getTime()) / 60000, 0) / faults.length)
    : null;

  // 续约：窗口内到期的主合同里，有非草稿续签合同（flow.prevContractNo 指向它）的占比
  const ended = contracts.filter((c) => c.status === "EXPIRED" && (c.flow?.contractKind ?? "MAIN") === "MAIN" && inWindow(c.endAt, from, to));
  const renewed = ended.filter((c) => contracts.some((r) => r.flow?.prevContractNo === c.contractNo && r.status !== "DRAFT")).length;
  const renewalRate = ratio(renewed, ended.length);

  // 线索：mock 线索没有建档时间，以 updatedAt 近似「窗口内活跃」
  const leadRows = leads.filter((l) => inWindow((l as { updatedAt?: string }).updatedAt, from, to));
  const signed = leadRows.filter((l) => l.stage === "SIGNED").length;

  return {
    from, to,
    woWithSla: slaRows.length, woSlaRate: ratio(slaOk, slaRows.length),
    faultResolved: faults.length, mttrMinutes: mttr,
    contractsEnded: ended.length, contractsRenewed: renewed, renewalRate,
    expiredNotRenewedRatio: renewalRate === null ? null : Math.round((1 - renewalRate) * 10000) / 10000,
    sitesWentLive: 0, installLeadDaysAvg: null,
    firstTrials: 0, firstTrialPassRate: null,
    leadsCreated: leadRows.length, leadsSigned: signed, leadConversionRate: ratio(signed, leadRows.length),
    signingCycleDaysAvg: null,
    removalCounted: 0, removalExpected: 0, removalRecoveryRate: null,
  };
}
