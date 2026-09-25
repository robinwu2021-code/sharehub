"use client";

// 告警管理 › 告警代码（方案 §8.2）。页内分段，菜单不变：
//
//   业务告警码（主视图）：按域分组；参数与近 30 天效果放在一起看，才调得动参
//                         （误报率 / 自愈率 / 撤单率超阈值标 warning）；每码可看 / 改根因路由。
//   存量设备码：业务告警上线之前的设备错误码字典（仍可编辑、归档）。
//   设备信号：只读字典 —— 信号**不是**告警，它喂给哪些业务告警、命中后挂什么保护。
//
// 字典本身有界（真后端 39 条），一次取完、不分页（UNPAGED_SIZE，见 PagedTable 的判据）。
import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Toolbar } from "@/components/ui/toolbar";
import { Button } from "@/components/ui/button";
import { StatusBadge, statusOptions } from "@/components/ui/status-badge";
import { HelpNote } from "@/components/ui/help-note";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { segmentedTrackClass, segmentedItemClass } from "@/components/ui/segmented";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { EnabledBadge } from "@/components/status";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions, archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";
import { cn } from "@/lib/utils";
import type { AlarmCode, AlarmDomain, SignalCode } from "@/lib/types";
import {
  ALARM_LEVEL, ALARM_DOMAIN, ALARM_DOMAINS, ALARM_PRIORITY, ALARM_DISPOSITION, ALARM_SUBJECT, ALARM_EVAL,
  roleLabel,
} from "./alarm-maps";
import { AlarmRouteEditor } from "./alarm-route-editor";

type View = "business" | "device" | "signals";

/** 效果列的告警阈值：超了标 warning。数值取方案 §8.2「超阈值 warning」的保守起点，调参后再收。 */
const RATE_WARN = { falseAlarmRate: 0.2, selfHealRate: 0.5, withdrawnRate: 0.3 } as const;

/**
 * 告警代码表单：只放基本信息。业务码的判定 / 处置参数（方案 §8.2 五组表单）本批未做，
 * 根因路由单独在「路由」抽屉里改。
 *
 * @form POST /api/ops/alarms/codes
 * @form POST /api/ops/alarms/codes/{code}
 */
const CODE_FIELDS = (business: boolean): FieldDef[] => [
  { key: "code", label: "告警代码", readOnlyOnEdit: true, placeholder: "SLOT_STUCK" },
  { key: "message", label: business ? "业务名称" : "告警信息", placeholder: business ? "站点借不到" : "卡槽卡宝" },
  { key: "level", label: "等级", type: "select", options: statusOptions(ALARM_LEVEL) },
  { key: "suggestion", label: "处置预案", placeholder: "远程弹仓一次；仍失败则锁槽并派维修" },
  // 业务码的「是否开单」由首选处置与根因路由决定，不再看这个开关
  ...(business ? [] : [{ key: "autoWorkOrder", label: "自动开工单", type: "switch" } as FieldDef]),
];

const pct = (v: number) => `${Math.round(v * 1000) / 10}%`;

function Rate({ v, warn }: { v: number | undefined; warn: number }) {
  if (v === undefined) return <span className="text-muted-foreground">-</span>;
  return <span className={cn("tabular-nums", v > warn && "text-warning-ink")}>{pct(v)}{v > warn ? " ▲" : ""}</span>;
}

export function AlarmCodesTab() {
  const qc = useQueryClient();
  const allow = useCan();
  const canConfig = allow("workorder:alarm:config");
  const canSignals = allow("device:cabinet:read");
  const { confirm, dialog } = useConfirm();
  const [view, setView] = React.useState<View>("business");
  const [keyword, setKeyword] = React.useState("");
  const [showArchived, setShowArchived] = React.useState(false);
  const [form, setForm] = React.useState<Partial<AlarmCode> | null>(null);
  const [routeOf, setRouteOf] = React.useState<AlarmCode | null>(null);

  const codesQ = useQuery({
    queryKey: ["alarm", "codes", "all", keyword, showArchived],
    queryFn: () => api.listAlarmCodes({ page: 1, size: UNPAGED_SIZE, keyword: keyword || undefined, showArchived }),
  });
  const statsQ = useQuery({ queryKey: ["alarm", "codeStats"], queryFn: () => api.alarmCodeStats(30) });
  const signalsQ = useQuery({
    queryKey: ["device", "signals"], queryFn: () => api.listSignalCodes(), enabled: view === "signals" && canSignals,
  });
  const stats = React.useMemo(() => new Map((statsQ.data ?? []).map((s) => [s.code, s])), [statsQ.data]);

  const all = codesQ.data?.list ?? [];
  const DOMAIN_ORDER = (d: AlarmDomain | null | undefined) => (d ? ALARM_DOMAINS.indexOf(d) : 99);
  const business = all.filter((c) => c.business)
    .sort((a, b) => DOMAIN_ORDER(a.business!.domain) - DOMAIN_ORDER(b.business!.domain) || a.code.localeCompare(b.code));
  const device = all.filter((c) => !c.business);

  const saveCode = useMutation({
    mutationFn: (v: Partial<AlarmCode>) => api.saveAlarmCode(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success("已保存"); setForm(null); },
  });
  const archiveM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => (v.undo ? api.unarchiveAlarmCode(v.no) : api.archiveAlarmCode(v.no)),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  const askArchive = async (c: AlarmCode, undo: boolean) => {
    if (await confirm(undo ? unarchiveConfirm("告警代码", c.code) : archiveConfirm("告警代码", c.code))) archiveM.mutate({ no: c.code, undo });
  };

  const actionsCell = (c: AlarmCode) => (
    <ArchiveActions
      archived={!!c.archivedAt}
      canWrite={canConfig}
      // 内置码由判定器产出，归档等于让一类业务告警静默消失
      canArchive={!c.business?.builtin}
      archiveHint="内置业务告警码不可归档：停用请关掉「启用」，而不是让它从字典里消失"
      onArchive={() => askArchive(c, false)}
      onUnarchive={() => askArchive(c, true)}
      actions={
        <div className="flex gap-1.5">
          <Button size="sm" variant="outline" onClick={() => setForm(c)}>编辑</Button>
          {c.business && <Button size="sm" variant="outline" onClick={() => setRouteOf(c)}>路由</Button>}
        </div>
      }
    />
  );

  const bizCols: Column<AlarmCode>[] = [
    {
      header: "域",
      cell: (c) => c.business?.domain ? <StatusBadge map={ALARM_DOMAIN} value={c.business.domain} /> : "-",
    },
    {
      header: "业务告警",
      cell: (c) => (
        <>
          <span className="txt-strong">{c.message}</span>
          <span className="block txt-caption text-muted-foreground tabular-nums">{c.code}{c.business?.builtin ? " · 内置" : ""}</span>
        </>
      ),
    },
    { header: "受影响对象", cell: (c) => (c.business?.subjectType ? ALARM_SUBJECT[c.business.subjectType] : "-") },
    { header: "等级", cell: (c) => <StatusBadge map={ALARM_LEVEL} value={c.level} className="whitespace-nowrap" /> },
    {
      header: "判定",
      className: "whitespace-nowrap",
      cell: (c) => {
        const b = c.business!;
        const t = b.evalType ? ALARM_EVAL[b.evalType] : "-";
        const extra = b.evalType === "STATE" && b.holdMinutes ? ` ${b.holdMinutes} 分钟`
          : b.evalType === "COUNT" ? ` ${b.windowMinutes ?? "-"} 分钟内 ≥${b.threshold ?? "-"} 次`
          : b.evalType === "METRIC" && b.threshold != null ? ` 阈值 ${b.threshold}` : "";
        return <span>{t}{extra}{b.businessHoursOnly ? <span className="block txt-caption text-muted-foreground">只在营业时间计时</span> : null}</span>;
      },
    },
    {
      header: "基准优先级",
      cell: (c) => c.business?.basePriority ? <StatusBadge map={ALARM_PRIORITY} value={c.business.basePriority} className="whitespace-nowrap" /> : "-",
    },
    {
      header: "首选处置",
      cell: (c) => {
        const b = c.business!;
        if (!b.disposition) return "-";
        return (
          <span>
            <StatusBadge map={ALARM_DISPOSITION} value={b.disposition} />
            {b.ownerRole && b.disposition !== "WORK_ORDER" && <span className="ms-1 txt-caption text-muted-foreground">{roleLabel(b.ownerRole)}</span>}
            {b.disposition === "WORK_ORDER" && b.woDelayMinutes ? <span className="block txt-caption text-muted-foreground">延迟 {b.woDelayMinutes} 分钟开单</span> : null}
          </span>
        );
      },
    },
    { header: "近 30 天", className: "text-end tabular-nums", cell: (c) => stats.get(c.code)?.total ?? 0 },
    { header: "误报率", className: "text-end", cell: (c) => <Rate v={stats.get(c.code)?.falseAlarmRate} warn={RATE_WARN.falseAlarmRate} /> },
    { header: "自愈率", className: "text-end", cell: (c) => <Rate v={stats.get(c.code)?.selfHealRate} warn={RATE_WARN.selfHealRate} /> },
    { header: "撤单率", className: "text-end", cell: (c) => <Rate v={stats.get(c.code)?.withdrawnRate} warn={RATE_WARN.withdrawnRate} /> },
    { header: "启用", cell: (c) => <EnabledBadge on={!!c.business?.enabled} /> },
    ...(showArchived ? [{ header: "归档时间", cell: (c: AlarmCode) => <ArchivedAt at={c.archivedAt} /> }] : []),
    { header: "操作", cell: actionsCell },
  ];

  const deviceCols: Column<AlarmCode>[] = [
    { header: "告警代码", cell: (c) => <span className="txt-strong tabular-nums">{c.code}</span> },
    { header: "告警信息", cell: (c) => c.message },
    { header: "等级", cell: (c) => <StatusBadge map={ALARM_LEVEL} value={c.level} className="whitespace-nowrap" /> },
    { header: "建议处置", cell: (c) => <span className="text-muted-foreground">{c.suggestion}</span> },
    { header: "自动开工单", cell: (c) => <EnabledBadge on={c.autoWorkOrder} onLabel="是" offLabel="否" /> },
    { header: "近 30 天", className: "text-end tabular-nums", cell: (c) => stats.get(c.code)?.total ?? 0 },
    ...(showArchived ? [{ header: "归档时间", cell: (c: AlarmCode) => <ArchivedAt at={c.archivedAt} /> }] : []),
    { header: "操作", cell: actionsCell },
  ];

  const signalCols: Column<SignalCode>[] = [
    { header: "信号码", cell: (s) => <span className="txt-strong tabular-nums">{s.code}</span> },
    { header: "名称", cell: (s) => s.name },
    { header: "类别", cell: (s) => s.category ?? "-" },
    { header: "作用范围", cell: (s) => s.scope ?? "-" },
    {
      header: "保护动作",
      cell: (s) => !s.protectiveAction || s.protectiveAction === "NONE"
        ? <span className="text-muted-foreground">不挂保护</span>
        : <span className="tabular-nums">{s.protectiveAction}</span>,
    },
    { header: "清除信号", cell: (s) => <span className="tabular-nums">{s.clearsCode ?? "-"}</span> },
    { header: "喂给哪些业务告警", cell: (s) => s.feeds ?? "-" },
  ];

  const views: { key: View; label: string; n?: number }[] = [
    { key: "business", label: "业务告警码", n: business.length },
    { key: "device", label: "存量设备码", n: device.length },
    { key: "signals", label: "设备信号" },
  ];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className={segmentedTrackClass()} role="group" aria-label="告警代码分类">
          {views.map((v) => (
            <button key={v.key} type="button" aria-pressed={view === v.key}
              className={segmentedItemClass(view === v.key, "px-3 py-1.5 text-sm")} onClick={() => setView(v.key)}>
              {v.label}{v.n !== undefined && <span className="ms-1 tabular-nums text-muted-foreground">{v.n}</span>}
            </button>
          ))}
        </div>
        <HelpNote title="三类有什么不同">
          <p>业务告警码回答「用户 / 生意受了什么影响」（站点借不到、付了款没拿到宝），由判定引擎产出，是告警中心看的那层。</p>
          <p className="mt-1">存量设备码是业务告警上线之前的设备错误码字典，只对历史告警有意义。</p>
          <p className="mt-1">设备信号是设备上报的原始事实（心跳、仓位卡宝），<b>不是告警</b>：它喂给业务告警、命中时挂保护动作。厂商错误码到信号的映射属于接入网关，这里只展示。</p>
          <p className="mt-1">效果列按近 30 天统计：误报率高 = 规则太敏感；自愈率高 = 开单延迟太短、不该派人；撤单率高 = 开出的单后来因告警自动恢复被撤。超阈值标 ▲。</p>
        </HelpNote>
      </div>

      {view !== "signals" && (
        <Toolbar
          search={keyword} onSearch={setKeyword} searchPlaceholder="搜索代码 / 名称 / 处置预案"
          onAdd={canConfig && view === "device" ? () => setForm({ level: "WARN", autoWorkOrder: false, message: "", suggestion: "" }) : undefined}
          addLabel="新增告警代码"
          onExport={() => exportCsv<AlarmCode>("告警代码", [
            { header: "告警代码", value: (c) => c.code },
            { header: "名称", value: (c) => c.message },
            { header: "域", value: (c) => (c.business?.domain ? ALARM_DOMAIN[c.business.domain].label : "-") },
            { header: "等级", value: (c) => ALARM_LEVEL[c.level]?.label ?? c.level },
            { header: "首选处置", value: (c) => (c.business?.disposition ? ALARM_DISPOSITION[c.business.disposition].label : "-") },
            { header: "处置预案", value: (c) => c.suggestion },
            { header: "近30天告警数", value: (c) => String(stats.get(c.code)?.total ?? 0) },
            { header: "误报率", value: (c) => (stats.get(c.code) ? pct(stats.get(c.code)!.falseAlarmRate) : "-") },
            { header: "自愈率", value: (c) => (stats.get(c.code) ? pct(stats.get(c.code)!.selfHealRate) : "-") },
            { header: "撤单率", value: (c) => (stats.get(c.code) ? pct(stats.get(c.code)!.withdrawnRate) : "-") },
          ], view === "business" ? business : device)}
        >
          <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
        </Toolbar>
      )}
      {view !== "signals" && !canConfig && (
        <ReadOnlyNotice what="告警配置" perm="workorder:alarm:config" note="不能编辑告警代码、改根因路由或归档" />
      )}

      {view === "business" && (
        <DataTable
          rowKey={(c) => c.code} columns={bizCols} rows={business} loading={codesQ.isLoading}
          error={codesQ.error} onRetry={() => codesQ.refetch()} rowClassName={archivedRowClass}
          empty={keyword ? "没有匹配的业务告警码——换个关键词试试。" : "还没有业务告警码——内置码由后端迁移种子写入；看到这句说明种子没跑，请联系技术。"}
        />
      )}
      {view === "device" && (
        <DataTable
          rowKey={(c) => c.code} columns={deviceCols} rows={device} loading={codesQ.isLoading}
          error={codesQ.error} onRetry={() => codesQ.refetch()} rowClassName={archivedRowClass}
          empty={showArchived ? "没有匹配的设备告警码——换个关键词试试。" : "没有在用的存量设备码——可能都已归档（打开「显示已归档」查看）。新告警走业务告警码，不必再补设备码。"}
        />
      )}
      {view === "signals" && (canSignals ? (
        <DataTable
          rowKey={(s) => s.code} columns={signalCols} rows={signalsQ.data} loading={signalsQ.isLoading}
          error={signalsQ.error} onRetry={() => signalsQ.refetch()}
          empty="信号字典为空——它由接入网关的迁移种子写入，空着说明种子没跑，请联系技术。"
        />
      ) : (
        <ReadOnlyNotice what="设备查看" perm="device:cabinet:read" note="看不到设备信号字典" />
      ))}

      <FormDrawer
        open={!!form} onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增告警代码" titleEdit={`编辑告警代码 ${form?.code ?? ""}`} isEdit={!!form?.code && all.some((c) => c.code === form.code)}
        fields={CODE_FIELDS(!!form?.business)} value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<AlarmCode>)}
        // 只提交表单里的字段：business 是出参里的只读投影，回写给后端没有意义
        onSubmit={() => form && saveCode.mutate({
          code: form.code, message: form.message, level: form.level, suggestion: form.suggestion,
          ...(form.business ? {} : { autoWorkOrder: form.autoWorkOrder }),
        })}
        submitting={saveCode.isPending}
      />
      <AlarmRouteEditor code={routeOf} onClose={() => setRouteOf(null)} />
      {dialog}
    </div>
  );
}

