"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { PricePlan, PricingDiff, PricingSchedule } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "templates", label: "计费模板" },
  { key: "diff", label: "差异化定价", phase: 2 as const },
  { key: "schedule", label: "活动/时段价", phase: 3 as const },
];

const PLAN_FIELDS: FieldDef[] = [
  { key: "planNo", label: "模板号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "name", label: "名称", placeholder: "标准计费" },
  { key: "scope", label: "适用", placeholder: "默认 / 点位 / 场景" },
  { key: "freeMinutes", label: "免费时长（分）", type: "number" },
  { key: "unitMinutes", label: "计费单位（分）", type: "number" },
  { key: "unitPrice", label: "单位价", type: "number" },
  { key: "capDaily", label: "日封顶", type: "number" },
  { key: "buyoutPrice", label: "买断价", type: "number" },
  { key: "currency", label: "币种", placeholder: "AED" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
];

const DIFF_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "scene", label: "场景", placeholder: "机场 / 医院 / 景区" },
  { key: "locationName", label: "点位", placeholder: "点位名称" },
  { key: "freeMinutes", label: "免费时长（分）", type: "number" },
  { key: "unitPrice", label: "单位价", type: "number" },
  { key: "capDaily", label: "日封顶", type: "number" },
  { key: "priority", label: "优先级", type: "number" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

const SCHEDULE_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "name", label: "名称", placeholder: "节假日高峰价" },
  { key: "period", label: "时段", placeholder: "如 周末 / 18:00-22:00" },
  { key: "multiplier", label: "倍率", type: "number" },
  { key: "active", label: "生效", type: "switch" },
];

function PricingInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "templates");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [planForm, setPlanForm] = useState<Partial<PricePlan> | null>(null);
  const [diffForm, setDiffForm] = useState<Partial<PricingDiff> | null>(null);
  const [scheduleForm, setScheduleForm] = useState<Partial<PricingSchedule> | null>(null);
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」只作用于计费模板 tab（TDD §10.1），切 tab 复位
  const [showArchived, setShowArchived] = useState(false);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);

  const canEdit = allow("pricing:rule:update");

  const plans = useQuery({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["priceplans", page, keyword, showArchived],
    queryFn: () => api.listPricePlans({ page, size: SIZE, keyword, showArchived }),
    placeholderData: keepPreviousData,
    enabled: tab === "templates",
  });
  const diffs = useQuery({
    queryKey: ["pricingdiffs", page, keyword],
    queryFn: () => api.listPricingDiffs({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "diff",
  });
  const schedules = useQuery({
    queryKey: ["pricingschedules", page, keyword],
    queryFn: () => api.listPricingSchedules({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "schedule",
  });

  const savePlan = useMutation({
    mutationFn: (v: Partial<PricePlan>) => api.savePricePlan(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["priceplans"] }); notify.success(t("common.success")); setPlanForm(null); },
  });
  const saveDiff = useMutation({
    mutationFn: (v: Partial<PricingDiff>) => api.savePricingDiff(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["pricingdiffs"] }); notify.success(t("common.success")); setDiffForm(null); },
  });
  const saveSchedule = useMutation({
    mutationFn: (v: Partial<PricingSchedule>) => api.savePricingSchedule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["pricingschedules"] }); notify.success(t("common.success")); setScheduleForm(null); },
  });

  // 归档 / 恢复（G1 软删除）。错误由全局 MutationCache 接管，页面不重复 catch。
  const archivePlan = useMutation({
    mutationFn: (no: string) => api.archivePricePlan(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["priceplans"] }); notify.success("已归档"); },
  });
  const unarchivePlan = useMutation({
    mutationFn: (no: string) => api.unarchivePricePlan(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["priceplans"] }); notify.success("已恢复"); },
  });

  const planCols: Column<PricePlan>[] = [
    { header: "模板号", cell: (p) => <span className="font-medium">{p.planNo}</span> },
    { header: "名称", cell: (p) => p.name },
    { header: "适用", cell: (p) => <span className="text-muted-foreground">{p.scope}</span> },
    { header: "免费时长", cell: (p) => `${p.freeMinutes} 分` },
    { header: "计费", cell: (p) => `${money(p.unitPrice, p.currency)} / ${p.unitMinutes} 分` },
    { header: "日封顶", cell: (p) => <span className="tabular-nums">{money(p.capDaily, p.currency)}</span> },
    { header: "买断价", cell: (p) => <span className="tabular-nums">{money(p.buyoutPrice, p.currency)}</span> },
    { header: "状态", cell: (p) => p.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    // 归档时间列只在「显示已归档」打开时出现，默认视图里整列都是 `-` 属于噪音
    ...(showArchived ? [{ header: "归档时间", cell: (p: PricePlan) => <ArchivedAt at={p.archivedAt} /> }] : []),
    {
      header: t("common.actions"),
      cell: (p) => (
        <ArchiveActions
          archived={!!p.archivedAt}
          canWrite={canEdit}
          actions={<Button size="sm" variant="outline" onClick={() => setPlanForm(p)}>{t("common.edit")}</Button>}
          // 计费模板不在主数据强确认清单里，不要求手输编号
          onArchive={async () => { if (await confirm(archiveConfirm("计费模板", p.planNo))) archivePlan.mutate(p.planNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("计费模板", p.planNo))) unarchivePlan.mutate(p.planNo); }}
        />
      ),
    },
  ];

  const diffCols: Column<PricingDiff>[] = [
    { header: "规则号", cell: (d) => <span className="font-medium">{d.ruleNo}</span> },
    { header: "场景", cell: (d) => <Badge tone="outline">{d.scene}</Badge> },
    { header: "点位", cell: (d) => <span className="text-muted-foreground">{d.locationName}</span> },
    { header: "免费时长", cell: (d) => `${d.freeMinutes} 分` },
    { header: "单位价", cell: (d) => <span className="tabular-nums">{money(d.unitPrice, d.currency)}</span> },
    { header: "日封顶", cell: (d) => <span className="tabular-nums">{money(d.capDaily, d.currency)}</span> },
    { header: "优先级", cell: (d) => <span className="tabular-nums">{d.priority}</span> },
    { header: t("common.actions"), cell: (d) => canEdit ? <Button size="sm" variant="outline" onClick={() => setDiffForm(d)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const scheduleCols: Column<PricingSchedule>[] = [
    { header: "规则号", cell: (s) => <span className="font-medium">{s.ruleNo}</span> },
    { header: "名称", cell: (s) => s.name },
    { header: "时段", cell: (s) => <span className="text-muted-foreground">{s.period}</span> },
    { header: "倍率", cell: (s) => <span className="tabular-nums">{s.multiplier.toFixed(2)}×</span> },
    { header: "状态", cell: (s) => s.active ? <Badge tone="success">生效中</Badge> : <Badge tone="muted">未生效</Badge> },
    { header: t("common.actions"), cell: (s) => canEdit ? <Button size="sm" variant="outline" onClick={() => setScheduleForm(s)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const cur = tab === "templates" ? plans : tab === "diff" ? diffs : schedules;

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
  const exportPlans = () => exportCsv<PricePlan>("计费模板", [
    { header: "模板号", value: (p) => p.planNo },
    { header: "名称", value: (p) => p.name },
    { header: "适用", value: (p) => p.scope },
    { header: "免费时长", value: (p) => `${p.freeMinutes} 分` },
    { header: "计费", value: (p) => `${money(p.unitPrice, p.currency)} / ${p.unitMinutes} 分` },
    { header: "日封顶", value: (p) => money(p.capDaily, p.currency) },
    { header: "买断价", value: (p) => money(p.buyoutPrice, p.currency) },
    { header: "状态", value: (p) => (p.status === "ACTIVE" ? "启用" : "停用") },
    ...(showArchived ? [{ header: "归档时间", value: (p: PricePlan) => (p.archivedAt ? fmtTime(p.archivedAt) : "") }] : []),
  ], plans.data?.list ?? []);
  const exportDiffs = () => exportCsv<PricingDiff>("差异化定价", [
    { header: "规则号", value: (d) => d.ruleNo },
    { header: "场景", value: (d) => d.scene },
    { header: "点位", value: (d) => d.locationName },
    { header: "免费时长", value: (d) => `${d.freeMinutes} 分` },
    { header: "单位价", value: (d) => money(d.unitPrice, d.currency) },
    { header: "日封顶", value: (d) => money(d.capDaily, d.currency) },
    { header: "优先级", value: (d) => d.priority },
  ], diffs.data?.list ?? []);
  const exportSchedules = () => exportCsv<PricingSchedule>("活动时段价", [
    { header: "规则号", value: (s) => s.ruleNo },
    { header: "名称", value: (s) => s.name },
    { header: "时段", value: (s) => s.period },
    { header: "倍率", value: (s) => `${s.multiplier.toFixed(2)}×` },
    { header: "状态", value: (s) => (s.active ? "生效中" : "未生效") },
  ], schedules.data?.list ?? []);
  // 无数据时不给导出按钮：导出一个空 CSV 只会让人以为功能坏了
  const exportIf = (fn: () => void, n?: number) => (n ? fn : undefined);

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setShowArchived(false); }} />

      {tab === "templates" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索模板名称 / 适用"
            onExport={exportIf(exportPlans, plans.data?.list?.length)}
            onAdd={canEdit ? () => setPlanForm({ scope: "默认", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 199, currency: "AED", status: "ACTIVE" }) : undefined}
            addLabel="新增计费模板"
          >
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
          </Toolbar>
          <DataTable
            rowKey={(p: PricePlan) => p.planNo}
            columns={planCols}
            rows={plans.data?.list}
            loading={plans.isLoading}
            rowClassName={archivedRowClass}
            empty={showArchived
              ? "没有匹配的计费模板——换个关键词试试"
              : "没有在用的计费模板——可能都已归档（打开「显示已归档」查看），或点「新增计费模板」建一条"}
          />
        </>
      )}

      {tab === "diff" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索规则号 / 场景 / 点位"
            onExport={exportIf(exportDiffs, diffs.data?.list?.length)}
            onAdd={canEdit ? () => setDiffForm({ scene: "", locationName: "", freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 10, currency: "AED" }) : undefined}
            addLabel="新增差异化规则"
          />
          <DataTable rowKey={(d: PricingDiff) => d.ruleNo} columns={diffCols} rows={diffs.data?.list} loading={diffs.isLoading}
            empty="暂无差异化规则——未配置时全部点位走计费模板，可点「新增差异化规则」为机场/医院等场景单独定价" />
        </>
      )}

      {tab === "schedule" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索规则号 / 名称 / 时段"
            onExport={exportIf(exportSchedules, schedules.data?.list?.length)}
            onAdd={canEdit ? () => setScheduleForm({ name: "", period: "", multiplier: 1.5, active: true }) : undefined}
            addLabel="新增活动/时段价"
          />
          <DataTable rowKey={(s: PricingSchedule) => s.ruleNo} columns={scheduleCols} rows={schedules.data?.list} loading={schedules.isLoading}
            empty="暂无活动/时段价——未配置时不做时段加价，可点「新增活动/时段价」设节假日或高峰倍率" />
        </>
      )}

      {cur.data && <Pagination page={page} size={SIZE} total={cur.data.total} onPage={setPage} />}

      <FormDrawer
        open={!!planForm}
        onOpenChange={(o) => !o && setPlanForm(null)}
        titleNew="新增计费模板"
        titleEdit={`编辑计费模板 ${planForm?.planNo ?? ""}`}
        isEdit={!!planForm?.planNo}
        fields={PLAN_FIELDS}
        value={(planForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPlanForm(v as Partial<PricePlan>)}
        onSubmit={() => planForm && savePlan.mutate(planForm)}
        submitting={savePlan.isPending}
      />

      <FormDrawer
        open={!!diffForm}
        onOpenChange={(o) => !o && setDiffForm(null)}
        titleNew="新增差异化规则"
        titleEdit={`编辑差异化规则 ${diffForm?.ruleNo ?? ""}`}
        isEdit={!!diffForm?.ruleNo}
        fields={DIFF_FIELDS}
        value={(diffForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setDiffForm(v as Partial<PricingDiff>)}
        onSubmit={() => diffForm && saveDiff.mutate(diffForm)}
        submitting={saveDiff.isPending}
      />

      <FormDrawer
        open={!!scheduleForm}
        onOpenChange={(o) => !o && setScheduleForm(null)}
        titleNew="新增活动/时段价"
        titleEdit={`编辑活动/时段价 ${scheduleForm?.ruleNo ?? ""}`}
        isEdit={!!scheduleForm?.ruleNo}
        fields={SCHEDULE_FIELDS}
        value={(scheduleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setScheduleForm(v as Partial<PricingSchedule>)}
        onSubmit={() => scheduleForm && saveSchedule.mutate(scheduleForm)}
        submitting={saveSchedule.isPending}
      />

      {dialog}
    </div>
  );
}

export default function PricingPage() {
  return <Suspense fallback={null}><PricingInner /></Suspense>;
}
