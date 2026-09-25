"use client";

// 告警管理 › 告警记录（方案 §8.1，按业务告警 v2）。
//
//  ┌ 摘要条：未关闭 · 其中严重 · 已处置未关闭 · 今日自愈（前两张可点筛选，R2）
//  ├ 业务域分段：全部 · [角色默认] · 可借 · 可还 · 交易 · 安全 · 资产 · 经营 · 履约 · 合作 · 资金（带未关闭数）
//  ├ 工具栏：搜索 · 状态 · 等级 · 根因 · 处置方式 · 层级
//  └ 列表：业务名称 · 受影响对象 · 站点 · 影响 · 根因 · 等级 · 处置优先级 · 处置 · 持续 · 状态
//
// 裁决 #3（2026-09-25）：设备错误码降为「信号」，告警中心看的是**业务告警**。
// 存量设备告警（业务告警上线之前的行）没有域，只在「全部」里出现，照样能看能关。
import * as React from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { usePaging } from "@/lib/hooks/use-paging";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge } from "@/components/ui/status-badge";
import { HelpNote } from "@/components/ui/help-note";
import { Skeleton } from "@/components/ui/misc";
import { segmentedTrackClass, segmentedItemClass } from "@/components/ui/segmented";
import { RefLink } from "@/components/ref-link";
import { exportCsv } from "@/lib/export-csv";
import { fmtTime, cn } from "@/lib/utils";
import type { AlarmRecord, AlarmCode, AlarmDomain, AlarmSummary } from "@/lib/types";
import {
  ALARM_STATUS, ALARM_LEVEL, ALARM_PRIORITY, ALARM_DOMAIN, ALARM_DOMAINS, ALARM_CAUSE, ALARM_DISPOSITION,
  ALARM_CLOSE_REASON, ALARM_SOURCE, durationText,
} from "./alarm-maps";
import { AlarmSubject, DispositionRef, impactText } from "./alarm-detail-drawer";

/**
 * 按岗位给默认的域（方案 §8.1）：运维先看「借不到 / 还不了 / 安全 / 资产」，客服先看交易。
 * **只是默认视图，不是权限** —— 切到「全部」照样看得见别的域。role 在这里只用于展示偏好，
 * 与 lib/auth 的约定（role 不参与判权）一致。
 */
const ROLE_DEFAULT: Partial<Record<string, { label: string; domains: AlarmDomain[] }>> = {
  OPS: { label: "运维关注", domains: ["AVAILABILITY", "RETURNABILITY", "SAFETY", "ASSET"] },
  CS: { label: "客服关注", domains: ["TRANSACTION"] },
};

/** 状态筛选：多一个「未关闭」= OPEN,ACKED（后端按逗号拆 IN）。 */
const STATUS_OPTIONS = [
  { value: "OPEN,ACKED", label: "未关闭" },
  { value: "OPEN", label: ALARM_STATUS.OPEN.label },
  { value: "ACKED", label: ALARM_STATUS.ACKED.label },
  { value: "CLOSED", label: ALARM_STATUS.CLOSED.label },
];
const LAYER_OPTIONS = [
  { value: "top", label: "只看顶层告警" },
  { value: "all", label: "含被取代的子告警" },
];

const sumOf = (s: AlarmSummary | undefined, pick: "open" | "critical", domains?: AlarmDomain[]) =>
  Object.entries(s?.byDomain ?? {}).reduce(
    (n, [d, c]) => n + (!domains || domains.includes(d as AlarmDomain) ? c?.[pick] ?? 0 : 0), 0);

export function AlarmRecordsTab({
  codes, onOpen,
}: {
  codes: Map<string, AlarmCode>;
  /** 打开详情（页面据此写 ?no=）。tab 指定时直接落在那个页签。 */
  onOpen: (alarmNo: string, tab?: "disposition") => void;
}) {
  const role = useAuth((s) => s.role);
  const preset = ROLE_DEFAULT[role];
  const paging = usePaging();
  const [domain, setDomain] = React.useState<string>(() => preset ? preset.domains.join(",") : "");
  const [keyword, setKeyword] = React.useState("");
  const [status, setStatus] = React.useState("OPEN,ACKED");
  const [level, setLevel] = React.useState("");
  const [cause, setCause] = React.useState("");
  const [disposition, setDisposition] = React.useState("");
  const [layer, setLayer] = React.useState("top");
  const set = <T,>(fn: (v: T) => void) => (v: T) => { fn(v); paging.reset(); };

  const summary = useQuery({ queryKey: ["alarm", "summary"], queryFn: () => api.alarmSummary() });
  const q = useQuery({
    queryKey: ["alarm", "records", paging.page, paging.size, keyword, status, level, cause, disposition, domain, layer],
    queryFn: () => api.listAlarmRecords({
      page: paging.page, size: paging.size, keyword: keyword || undefined,
      status: status || undefined, level: level || undefined, cause: cause || undefined,
      disposition: disposition || undefined, domain: domain || undefined, topOnly: layer === "top",
    }),
    placeholderData: keepPreviousData,
  });

  const s = summary.data;
  const nameOf = (code: string) => codes.get(code)?.message ?? code;

  const cols: Column<AlarmRecord>[] = [
    {
      header: "业务告警",
      // 业务名称是扫描锚点（txt-strong）；告警号与码退到第二行小字 —— 运营读的是「站点借不到」，不是 ALM…
      cell: (a) => (
        <button type="button" className="text-start" onClick={() => onOpen(a.alarmNo)}>
          <span className="txt-strong text-primary hover:underline">{nameOf(a.alarmCode)}</span>
          {a.count > 1 && <span className="ms-1.5 txt-caption text-warning-ink tabular-nums">×{a.count}</span>}
          <span className="block txt-caption text-muted-foreground tabular-nums">{a.alarmNo} · {a.alarmCode}</span>
        </button>
      ),
    },
    { header: "受影响对象", cell: (a) => <AlarmSubject a={a} /> },
    { header: "站点", cell: (a) => <RefLink kind="site" no={a.siteNo} /> },
    { header: "影响", className: "whitespace-nowrap", cell: (a) => <span className="text-muted-foreground">{impactText(a)}</span> },
    {
      header: "根因",
      cell: (a) => a.business?.cause
        ? <StatusBadge map={ALARM_CAUSE} value={a.business.cause} />
        : <StatusBadge map={ALARM_SOURCE} value={a.source} />,
    },
    { header: "等级", cell: (a) => <StatusBadge map={ALARM_LEVEL} value={a.level} className="whitespace-nowrap" /> },
    {
      header: "处置优先级",
      cell: (a) => a.business?.priority
        ? <StatusBadge map={ALARM_PRIORITY} value={a.business.priority} className="whitespace-nowrap" />
        : <span className="text-muted-foreground">-</span>,
    },
    {
      header: "处置",
      cell: (a) => {
        const b = a.business;
        if (b?.dispositionType) {
          return (
            <span className="inline-flex flex-wrap items-center gap-1">
              <StatusBadge map={ALARM_DISPOSITION} value={b.dispositionType} />
              <DispositionRef type={b.dispositionType} refNo={b.dispositionRef} />
            </span>
          );
        }
        if (!b && a.workOrderNo) return <RefLink kind="wo" no={a.workOrderNo} />;
        if (a.status === "CLOSED") return <span className="text-muted-foreground">无需处置</span>;
        return (
          <button type="button" className="txt-caption text-primary hover:underline" onClick={() => onOpen(a.alarmNo, "disposition")}>
            待处置 · 看预案
          </button>
        );
      },
    },
    {
      header: "持续",
      className: "whitespace-nowrap tabular-nums",
      cell: (a) => durationText(a.business?.firstOccurredAt ?? a.occurredAt, a.business?.recoveredAt ?? a.closedAt),
    },
    {
      header: "状态",
      cell: (a) => (
        <span className="inline-flex flex-col items-start gap-0.5">
          <StatusBadge map={ALARM_STATUS} value={a.status} />
          {a.closeReason && <span className="txt-caption text-muted-foreground">{ALARM_CLOSE_REASON[a.closeReason]?.label ?? a.closeReason}</span>}
        </span>
      ),
    },
  ];

  const openTotal = sumOf(s, "open");
  const critTotal = sumOf(s, "critical");
  const isCritFilter = level === "CRITICAL" && status === "OPEN,ACKED";
  const isOpenFilter = !level && status === "OPEN,ACKED";

  const segs: { key: string; label: string; n: number }[] = [
    { key: "", label: "全部", n: openTotal },
    ...(preset ? [{ key: preset.domains.join(","), label: preset.label, n: sumOf(s, "open", preset.domains) }] : []),
    ...ALARM_DOMAINS
      // 预设只有一个域时（客服 = 交易）不再重复列一段
      .filter((d) => !(preset && preset.domains.length === 1 && preset.domains[0] === d))
      .map((d) => ({ key: d, label: ALARM_DOMAIN[d].label, n: sumOf(s, "open", [d]) })),
  ];

  return (
    <div>
      {/* 摘要条：一张卡 = 一个待办子集，可点的直接筛下面的列表（R2） */}
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        {summary.isLoading ? Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-24" />) : (
          <>
            <StatButton label="未关闭" value={openTotal} sub="待处理 + 已受理（只数顶层）" active={isOpenFilter}
              onClick={() => { set(setStatus)("OPEN,ACKED"); setLevel(""); }} />
            <StatButton label="其中严重" value={critTotal} sub="▲▲ 等级、未关闭" active={isCritFilter} tone="danger"
              onClick={() => { set(setStatus)("OPEN,ACKED"); setLevel("CRITICAL"); }} />
            <StatButton label="已处置未关闭" value={s?.disposedOpen ?? 0}
              sub="开了单不等于好了：工单完工复核后才关" />
            <StatButton label="今日自愈" value={s?.autoRecoveredToday ?? 0}
              sub="自愈占比高 = 规则可能太敏感" />
          </>
        )}
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className={segmentedTrackClass("max-w-full flex-wrap")} role="group" aria-label="按业务域筛选">
          {segs.map((g) => (
            <button
              key={g.key || "all"} type="button" aria-pressed={domain === g.key}
              className={segmentedItemClass(domain === g.key, "px-3 py-1.5 text-sm")}
              onClick={() => set(setDomain)(g.key)}
            >
              {g.label}
              <span className={cn("ms-1 tabular-nums", domain === g.key ? "text-foreground" : "text-muted-foreground")}>{g.n}</span>
            </button>
          ))}
        </div>
        <HelpNote title="域是什么">
          告警按「谁受影响」分域，而不是按设备部件：可借 / 可还是用户借还受阻，交易是付了款却没拿到宝这类，
          合作 / 经营 / 资金类多数落到待办中心由 BD、财务办理。
          存量设备告警（业务告警上线之前产生的）没有域，只在「全部」里出现。
          段上的数字是该域未关闭的顶层告警数。
        </HelpNote>
      </div>

      <Toolbar
        search={keyword} onSearch={set(setKeyword)} searchPlaceholder="搜索告警号 / 告警码 / 柜号 / 站点号 / 主体编号"
        onExport={() => exportCsv<AlarmRecord>("告警记录", [
          { header: "告警号", value: (a) => a.alarmNo },
          { header: "业务告警", value: (a) => nameOf(a.alarmCode) },
          { header: "告警码", value: (a) => a.alarmCode },
          { header: "域", value: (a) => (a.business?.domain ? ALARM_DOMAIN[a.business.domain].label : "-") },
          { header: "受影响对象", value: (a) => a.business?.subjectNo ?? a.cabinetNo ?? "-" },
          { header: "站点", value: (a) => a.siteNo ?? "-" },
          { header: "影响", value: (a) => impactText(a) },
          { header: "根因", value: (a) => (a.business?.cause ? ALARM_CAUSE[a.business.cause]?.label ?? a.business.cause : "-") },
          { header: "等级", value: (a) => ALARM_LEVEL[a.level]?.label ?? a.level },
          { header: "处置优先级", value: (a) => (a.business?.priority ? ALARM_PRIORITY[a.business.priority].label : "-") },
          { header: "处置", value: (a) => `${a.business?.dispositionType ? ALARM_DISPOSITION[a.business.dispositionType].label : "-"} ${a.business?.dispositionRef ?? a.workOrderNo ?? ""}`.trim() },
          { header: "发生时间", value: (a) => fmtTime(a.occurredAt) },
          { header: "状态", value: (a) => ALARM_STATUS[a.status]?.label ?? a.status },
          { header: "关闭原因", value: (a) => (a.closeReason ? ALARM_CLOSE_REASON[a.closeReason]?.label ?? a.closeReason : "-") },
        ], q.data?.list ?? [])}
      >
        <FilterSelect value={status} onChange={set(setStatus)} allLabel="全部状态" options={STATUS_OPTIONS} aria-label="按状态筛选" />
        <FilterSelect value={level} onChange={set(setLevel)} allLabel="全部等级" options={ALARM_LEVEL} aria-label="按等级筛选" />
        <FilterSelect value={cause} onChange={set(setCause)} allLabel="全部根因" options={ALARM_CAUSE} aria-label="按根因筛选" />
        <FilterSelect value={disposition} onChange={set(setDisposition)} allLabel="全部处置方式" options={ALARM_DISPOSITION} aria-label="按处置方式筛选" />
        <FilterSelect value={layer} onChange={set(setLayer)} options={LAYER_OPTIONS} aria-label="按告警层级筛选" />
      </Toolbar>

      <PagedTable
        query={q}
        paging={paging}
        rowKey={(a) => a.alarmNo}
        columns={cols}
        rowClassName={(a) => (a.business?.parentAlarmNo ? "opacity-70" : undefined)}
        empty={status === "OPEN,ACKED" && !keyword
          ? "当前筛选下没有未关闭的告警——业务在正常运转。想看历史，把状态切到「全部状态」或「已关闭」。"
          : "没有匹配的告警——试着清空关键词，或放宽状态 / 等级 / 根因 / 处置方式筛选。"}
      />
    </div>
  );
}

/** 可点的摘要卡（R2）。不给 onClick 的是纯展示卡：没有对应的列表筛选，点了也筛不出那个子集。 */
function StatButton({
  label, value, sub, active, onClick, tone,
}: {
  label: string; value: number; sub?: string; active?: boolean; onClick?: () => void; tone?: "danger";
}) {
  const body = (
    <>
      <div className="txt-body text-muted-foreground">{label}</div>
      <div className={cn("mt-2 txt-display tabular-nums", tone === "danger" && value > 0 && "text-destructive-ink")}>{value}</div>
      {sub && <div className="mt-1 txt-caption text-muted-foreground">{sub}</div>}
    </>
  );
  const base = "rounded-card bg-card p-5 text-start shadow-[var(--card-shadow)]";
  if (!onClick) return <div data-surface="stat" className={base}>{body}</div>;
  return (
    <button
      type="button" data-surface="stat" aria-pressed={!!active} onClick={onClick}
      className={cn(base, "border transition-colors duration-[var(--dur)]",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        active ? "border-primary" : "border-transparent hover:border-primary/40")}
    >
      {body}
    </button>
  );
}
