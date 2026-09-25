"use client";

// 工单详情抽屉（方案 §8.5 改造）：
//
//   DetailHeader：工单号 · 类型 · [状态] · 优先级 · SLA            [主动作] ⋯
//   ● 创建 ── ● 派单 ── ● 接单 ── ● 处理 ── ○ 完工 ── ○ 验收
//   概要 │ 关联告警 │ 处理记录 │ 现场照片
//
// 状态只能由头部的动作改（规则 R1），抽屉里没有任何状态下拉。
// **关联告警是这里最要紧的一页**：一张维修单可能压着好几条告警，
// 修完不看就关单，那几条会继续躺在告警中心 —— 告警数长期虚高，最后没人再信它。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs } from "@/components/ui/tabs";
import { Timeline } from "@/components/ui/timeline";
import { ErrorState } from "@/components/ui/misc";
import { StateActions, type ActionSpec } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";
import { WoStatusBadge, WO_TYPE_LABEL } from "@/components/status";
import { useFileUrl } from "@/components/ui/file-field";
import { fmtTime } from "@/lib/utils";
import { fileSize, type FileRef, type WorkOrder, type WorkOrderDetail } from "@/lib/types";
import { SlaRemain } from "./sla-remain";
import {
  ALARM_LEVEL, ALARM_RECOVERY, AUDIT_LABEL, CLOSE_REASON_LABEL, DISPATCH_STRATEGY_LABEL, FAULT_REASON_LABEL,
  PRIO, REVIEW, SOURCE_LABEL, TIMELINE_ACTION, WO_STEPS, stepOf,
} from "./wo-meta";

type Tab = "summary" | "alarms" | "timeline" | "photos";

export function WoDetailDrawer({
  woNo, onClose, actions,
}: {
  woNo: string | null;
  onClose: () => void;
  /** 当前工单可做的动作（页面按迁移表 + 权限算好；抽屉只负责摆放）。 */
  actions: (w: WorkOrder) => ActionSpec[];
}) {
  const q = useQuery({
    queryKey: ["wo-detail", woNo],
    queryFn: () => api.getWorkOrderDetail(woNo!),
    enabled: !!woNo,
  });
  const [tab, setTab] = React.useState<Tab>("summary");
  React.useEffect(() => { setTab("summary"); }, [woNo]);

  const d = q.data;
  const w = d?.order;
  const tabs = [
    { key: "summary", label: "概要" },
    { key: "alarms", label: `关联告警${d ? ` ${d.alarms.length}` : ""}` },
    { key: "timeline", label: "处理记录" },
    { key: "photos", label: `现场照片${d ? ` ${d.photos.length}` : ""}` },
  ];

  return (
    <Drawer
      open={!!woNo}
      onOpenChange={(o) => !o && onClose()}
      title={`工单 ${woNo ?? ""}`}
      desc="从开单到关单的完整留痕"
      width="w-[640px]"
    >
      {q.isLoading && <span className="text-muted-foreground">加载中…</span>}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {w && d && (
        <>
          <DetailHeader
            no={w.woNo}
            title={`${WO_TYPE_LABEL[w.type] ?? w.type} · ${w.locationName ?? w.cabinetNo ?? "未挂设备"}`}
            badge={<><WoStatusBadge s={w.status} /><StatusBadge map={PRIO} value={w.priority} className="whitespace-nowrap" /></>}
            meta={<>SLA <SlaRemain w={w} /> · 处理人 {w.handlerName ?? w.assigneeName ?? "未派单"}</>}
            stepper={<WoStepper w={w} />}
            actions={<StateActions actions={actions(w)} />}
          />
          <div className="mt-4">
            <Tabs tabs={tabs} value={tab} onChange={(k) => setTab(k as Tab)} />
            {tab === "summary" && <SummaryTab w={w} d={d} />}
            {tab === "alarms" && <AlarmsTab w={w} d={d} />}
            {tab === "timeline" && <TimelineTab d={d} />}
            {tab === "photos" && <PhotosTab photos={d.photos} />}
          </div>
        </>
      )}
    </Drawer>
  );
}

/** 步骤条：驳回 / 返工次数不改变主干位置；撤单 / 并单（关单原因）作为分支终态显示。 */
function WoStepper({ w }: { w: WorkOrder }) {
  const cr = w.ops?.closeReason;
  const branch = w.status === "CLOSED" && (cr === "WITHDRAWN" || cr === "DUPLICATE" || cr === "INVALID")
    ? { label: CLOSE_REASON_LABEL[cr], after: w.completedAt ? "DONE" : w.acceptedAt ? "ACCEPTED" : w.dispatchedAt ? "DISPATCHED" : "CREATED" }
    : null;
  return <StatusStepper steps={WO_STEPS} current={stepOf(w.status)} branch={branch} />;
}

/** 来源：业务号能跳就跳（R3）。告警来源的 sourceNo 是合并键（ALM:柜号:类型），不是告警号，跳关联告警。 */
function SourceRef({ w, d }: { w: WorkOrder; d: WorkOrderDetail }) {
  const label = SOURCE_LABEL[w.source] ?? w.source;
  if (w.source === "ALERT") {
    const first = d.alarms[0]?.alarmNo ?? (w.sourceNo?.startsWith("ALM") && !w.sourceNo.includes(":") ? w.sourceNo : null);
    return <>{label} · {first ? <RefLink kind="alarm" no={first} /> : <span className="text-muted-foreground">{w.sourceNo ?? "-"}</span>}
      {d.alarms.length > 1 && <span className="text-muted-foreground"> 等 {d.alarms.length} 条</span>}</>;
  }
  if (w.source === "INSPECTION" && w.sourceNo) {
    return <>{label} · <RefLink kind="wo" no={w.sourceNo.split(":")[0]} /></>;
  }
  if (w.source === "PLAN" && w.sourceNo) return <>{label} · <PlanRef planNo={w.sourceNo} /></>;
  return <>{label}{w.sourceNo ? <span className="text-muted-foreground tabular-nums"> · {w.sourceNo}</span> : null}</>;
}

/** 巡检计划来源：带出路线与负责人（计划没有详情页，就地说清是哪条线）。 */
function PlanRef({ planNo }: { planNo: string }) {
  const q = useQuery({ queryKey: ["inspection-plan", planNo], queryFn: () => api.getInspectionPlan(planNo), retry: false });
  return (
    <span className="tabular-nums">
      {planNo}
      {q.data && <span className="text-muted-foreground"> · {q.data.route} · 负责人 {q.data.assignee}</span>}
    </span>
  );
}

function SummaryTab({ w, d }: { w: WorkOrder; d: WorkOrderDetail }) {
  const o = w.ops;
  return (
    <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
      <Field label="类型 / 来源"><>{WO_TYPE_LABEL[w.type] ?? w.type} · <SourceRef w={w} d={d} /></></Field>
      <Field label="设备 / 站点">
        <RefLink kind="cabinet" no={w.cabinetNo} /> · {o?.siteNo ? <RefLink kind="site" no={o.siteNo} label={w.locationName ?? o.siteNo} /> : (w.locationName ?? "-")}
      </Field>
      <Field label="问题描述" className="sm:col-span-2">{w.description}</Field>
      <Field label="处理人">
        {w.handlerName ?? w.assigneeName ?? "未派单"}
        {o?.assigneeType && <span className="text-muted-foreground">（{o.assigneeType === "AGENT" ? "代理承接" : "平台员工"}）</span>}
      </Field>
      <Field label="SLA 解决时限">
        <SlaRemain w={w} />{w.slaDueAt && <span className="text-muted-foreground"> · 截止 {fmtTime(w.slaDueAt.replace(" ", "T"))}</span>}
      </Field>
      <Field label="创建 / 期望完成">{fmtTime(w.createdAt)} · {w.expectedAt ? fmtTime(w.expectedAt) : "未设定"}</Field>
      <Field label="派单 / 接单">
        {w.dispatchedAt ? fmtTime(w.dispatchedAt) : "未派单"} · {w.acceptedAt ? fmtTime(w.acceptedAt) : "未接单"}
      </Field>
      <Field label="完工">{w.completedAt ? `${fmtTime(w.completedAt)}${w.partsReplaced ? " · 更换了配件" : ""}` : "未完工"}</Field>
      <Field label="故障原因">{o?.faultReasonCode ? FAULT_REASON_LABEL[o.faultReasonCode] ?? o.faultReasonCode : "-"}</Field>
      <Field label="完工复核">{o?.reviewStatus ? <StatusBadge map={REVIEW} value={o.reviewStatus} /> : <span className="text-muted-foreground">不适用</span>}</Field>
      <Field label="验收">
        {w.auditedAt
          ? `${w.auditorName ?? "-"} · ${fmtTime(w.auditedAt)} · ${w.auditResult ? AUDIT_LABEL[w.auditResult] : "-"}`
          : "未验收"}
      </Field>
      {w.auditNote && <Field label="验收说明" className="sm:col-span-2">{w.auditNote}</Field>}
      {o?.closeReason && <Field label="关单原因">{CLOSE_REASON_LABEL[o.closeReason] ?? o.closeReason}</Field>}
      {o?.mergedIntoWoNo && <Field label="并入工单"><RefLink kind="wo" no={o.mergedIntoWoNo} /></Field>}
      {!!w.rejectCount && <Field label="驳回 / 返工" className="sm:col-span-2">已退回 {w.rejectCount} 次；最近原因：{w.rejectReason ?? w.auditNote ?? "-"}</Field>}
    </div>
  );
}

function AlarmsTab({ w, d }: { w: WorkOrder; d: WorkOrderDetail }) {
  const review = w.ops?.reviewStatus;
  if (d.alarms.length === 0) {
    return (
      <p className="txt-body text-muted-foreground">
        这张单没有关联告警 —— {w.source === "ALERT" ? "告警可能已被并入别的工单或已撤单" : "它不是告警转来的（手工开单 / 投诉 / 巡检）"}，
        完工后不做自动复核，由验收人人工判定。
      </p>
    );
  }
  const still = d.alarms.filter((a) => a.status !== "CLOSED").length;
  return (
    <div className="space-y-3">
      <p className="txt-body text-muted-foreground">
        完工后系统逐条复核：全部 ✓ 自动验收；有 ✗ 则标「复核未通过」，等人返工或写明理由放行。
        {review && <> 当前：<StatusBadge map={REVIEW} value={review} /></>}
        {!review && still > 0 && <> 目前 {still} 条仍在。</>}
      </p>
      <ul className="divide-y divide-border">
        {d.alarms.map((a) => (
          <li key={a.alarmNo} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
            <StatusBadge map={ALARM_RECOVERY} value={a.status === "CLOSED" ? "RECOVERED" : "ACTIVE"} />
            <RefLink kind="alarm" no={a.alarmNo} className="txt-strong" />
            <span className="txt-body">{a.alarmCode}</span>
            <StatusBadge map={ALARM_LEVEL} value={a.level} />
            <span className="ms-auto txt-caption text-muted-foreground tabular-nums">
              {fmtTime(a.occurredAt?.includes("T") ? a.occurredAt : a.occurredAt?.replace(" ", "T"))}
              {a.count > 1 && ` · 重复 ${a.count} 次`}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function TimelineTab({ d }: { d: WorkOrderDetail }) {
  const items = d.timeline.map((t, i) => {
    const key = t.action ?? t.kind;
    const note = t.kind === "DISPATCH" && t.note && DISPATCH_STRATEGY_LABEL[t.note]
      ? DISPATCH_STRATEGY_LABEL[t.note]
      : t.note?.replace(/^(COMPLETE|REWORK|NOTE):\s*/, "");
    const extra = [
      t.faultReasonCode ? `故障原因：${FAULT_REASON_LABEL[t.faultReasonCode as keyof typeof FAULT_REASON_LABEL] ?? t.faultReasonCode}` : null,
      t.fileNos.length ? `照片 ${t.fileNos.length} 张` : null,
    ].filter(Boolean).join(" · ");
    return {
      key: `${i}-${t.at}`,
      badge: { label: TIMELINE_ACTION[key]?.label ?? key, tone: TIMELINE_ACTION[key]?.tone ?? "outline" },
      meta: `${fmtTime(t.at)} · ${t.actor ?? "-"}`,
      change: extra || undefined,
      text: note || undefined,
    };
  });
  return <Timeline items={items} empty="还没有任何处理记录——派单、接单、现场处理、完工都会在这里按时间留痕。" />;
}

function PhotosTab({ photos }: { photos: FileRef[] }) {
  if (photos.length === 0) {
    return <p className="txt-body text-muted-foreground">没有现场照片 —— 照片在完工时上传；维修 / 装机 / 撤机单完工必须至少一张。</p>;
  }
  return (
    <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {photos.map((f) => <PhotoTile key={f.fileNo} f={f} />)}
    </ul>
  );
}

function PhotoTile({ f }: { f: FileRef }) {
  const url = useFileUrl(f.previewable ? f.fileNo : undefined);
  return (
    <li>
      <a
        href={url} target="_blank" rel="noreferrer"
        className="flex aspect-square items-center justify-center overflow-hidden rounded-control bg-secondary"
        aria-disabled={!url}
      >
        {url
          // eslint-disable-next-line @next/next/no-img-element -- 限时签名地址，不走 next/image
          ? <img src={url} alt={f.originalName} className="size-full object-cover" />
          : <span className="txt-caption text-muted-foreground">{f.previewable ? "加载中…" : "不可预览"}</span>}
      </a>
      <div className="mt-1 truncate txt-caption text-muted-foreground" title={f.originalName}>
        {f.originalName} · {fileSize(f.sizeBytes)} · {fmtTime(f.uploadedAt)}
      </div>
    </li>
  );
}
