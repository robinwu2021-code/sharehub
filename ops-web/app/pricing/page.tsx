"use client";

import { Suspense, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Notice } from "@/components/ui/notice";
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
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { PricePlan, PricingDiff, PricingDimension, PricingSchedule, PeriodSpec } from "@/lib/types";
import { WEEKDAY_OPTIONS, TIME_PATTERN, PERIOD_ANY_DAY, PRICING_DIMENSION_LABEL, parsePeriod, formatPeriod } from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
const TAB_KEYS = ["templates", "diff", "schedule"] as const;

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

// —— 差异化定价三维（对齐后端 PriceRule.dimension）——
// 徽标映射（页面业务语义）：文案来自类型层 SSOT，只在这里配色。
const DIFF_DIM: StatusMap<PricingDimension> = {
  SITE: { label: PRICING_DIMENSION_LABEL.SITE, tone: "default" },
  LOCATION: { label: PRICING_DIMENSION_LABEL.LOCATION, tone: "success" },
  SCENE: { label: PRICING_DIMENSION_LABEL.SCENE, tone: "warning" },
};
const DIM_OPTIONS = (Object.keys(DIFF_DIM) as PricingDimension[]).map((k) => ({ value: k, label: DIFF_DIM[k].label }));

// 差异化规则字段：目标是下拉（选项按维度取各自的真实实体），冗余列由服务端按 matchRef 带出，故不出输入框。
const diffFields = (
  dim: PricingDimension,
  targetOptions: { value: string; label: string }[],
  targetHint: string,
): FieldDef[] => [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "自动生成" },
  {
    key: "dimension", label: "维度", type: "select", required: true, options: DIM_OPTIONS,
    help: "站点=单站取价 · 点位=站内单点取价 · 场景=同场景全部站点；切维度会清空已选目标",
  },
  {
    key: "matchRef", label: PRICING_DIMENSION_LABEL[dim], type: "select", required: true,
    options: [{ value: "", label: `请选择${PRICING_DIMENSION_LABEL[dim]}` }, ...targetOptions],
    help: targetHint,
  },
  { key: "freeMinutes", label: "免费时长（分）", type: "number", min: 0 },
  { key: "unitPrice", label: "单位价", type: "number", min: 0 },
  { key: "capDaily", label: "日封顶", type: "number", min: 0 },
  { key: "priority", label: "优先级", type: "number", min: 1, help: "命中多条时数值小者优先" },
  { key: "currency", label: "币种", placeholder: "AED" },
];

// 时段编辑器字段：星期多选 + 时刻区间（可解析），或自定义表达式（日历事件）。
// 两组用 disabledWhen 互斥——FormDrawer 会清空被禁用字段的值，避免提交两套并存的脏数据。
const scheduleFields = (spec: PeriodSpec): FieldDef[] => [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "name", label: "名称", required: true, placeholder: "节假日高峰价" },
  {
    key: "kind", label: "时段类型", type: "select", section: "时段",
    options: [{ value: "RANGE", label: "按星期 / 时刻" }, { value: "EXPR", label: "自定义表达式（节假日等）" }],
    help: `将存为：${formatPeriod(spec) || "（空）"}`,
  },
  {
    key: "days", label: "星期", type: "multiselect", csv: true, section: "时段",
    options: WEEKDAY_OPTIONS, placeholder: `留空 = ${PERIOD_ANY_DAY}`,
    disabledWhen: (v) => v.kind === "EXPR",
  },
  {
    key: "from", label: "开始时刻", section: "时段", placeholder: "18:00",
    pattern: { re: TIME_PATTERN, msg: "时刻格式为 HH:mm（24 时制）" },
    disabledWhen: (v) => v.kind === "EXPR",
    help: "两个时刻都留空 = 全天；跨零点（22:00-06:00）合法",
  },
  {
    key: "to", label: "结束时刻", section: "时段", placeholder: "22:00",
    pattern: { re: TIME_PATTERN, msg: "时刻格式为 HH:mm（24 时制）" },
    disabledWhen: (v) => v.kind === "EXPR",
  },
  {
    key: "expr", label: "自定义表达式", section: "时段", placeholder: "公共假日 / 斋月全月",
    disabledWhen: (v) => v.kind === "RANGE",
    help: "星期+时刻表达不了的日历事件走这里，原文照存",
  },
  { key: "multiplier", label: "倍率", type: "number", required: true, min: 0.1, max: 9.99 },
  { key: "active", label: "生效", type: "switch" },
];

/** 时段草稿 = 落库字段 + 仅编辑期存在的结构化字段（提交前折叠回 period）。 */
type ScheduleDraft = Partial<PricingSchedule> & Partial<PeriodSpec>;
const specOf = (d: ScheduleDraft | null): PeriodSpec => ({
  kind: d?.kind === "EXPR" ? "EXPR" : "RANGE",
  days: d?.days ?? "", from: d?.from ?? "", to: d?.to ?? "", expr: d?.expr ?? "",
});
/** 打开编辑时把已存的 period 解析成结构化；解析不了的落到 EXPR 原文，不丢用户的老表达式。 */
const draftOf = (s: PricingSchedule): ScheduleDraft => ({ ...s, ...parsePeriod(s.period) });

function PricingInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setKeyword(""); setShowArchived(false); };
  const tabs = useNavTabs("/pricing", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const [keyword, setKeyword] = useState("");
  const [planForm, setPlanForm] = useState<Partial<PricePlan> | null>(null);
  const [diffForm, setDiffForm] = useState<Partial<PricingDiff> | null>(null);
  const [scheduleForm, setScheduleForm] = useState<ScheduleDraft | null>(null);
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」只作用于计费模板 tab（TDD §10.1），切 tab 复位
  const [showArchived, setShowArchived] = useState(false);

  const canEdit = allow("pricing:rule:update");

  const plans = useQuery({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["priceplans", paging.page, paging.size, keyword, showArchived],
    queryFn: () => api.listPricePlans({ page: paging.page, size: paging.size, keyword, showArchived }),
    placeholderData: keepPreviousData,
    enabled: tab === "templates",
  });
  const diffs = useQuery({
    queryKey: ["pricingdiffs", paging.page, paging.size, keyword],
    queryFn: () => api.listPricingDiffs({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "diff",
  });
  const schedules = useQuery({
    queryKey: ["pricingschedules", paging.page, paging.size, keyword],
    queryFn: () => api.listPricingSchedules({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "schedule",
  });
  // 目标下拉的选项源：差异化规则必须指向真实实体，按维度读各自的表。
  // size=200 一次拉全——下拉不分页，分页的下拉会让人以为「我的站点不见了」。
  const sitesQ = useQuery({
    queryKey: ["pricing-sites"],
    queryFn: () => api.listSites({ page: 1, size: 200 }),
    enabled: tab === "diff",
  });
  const locationsQ = useQuery({
    queryKey: ["pricing-locations"],
    queryFn: () => api.listLocations({ page: 1, size: 200 }),
    enabled: tab === "diff",
  });
  const diffDim: PricingDimension = diffForm?.dimension ?? "SITE";
  // 三套选项源按当前维度取一套；场景选项 = 站点表里真实出现过的 sceneType 去重（与 mock 校验同口径）
  const targetOptions = useMemo(() => {
    if (diffDim === "LOCATION") {
      return (locationsQ.data?.list ?? []).map((l) => ({ value: l.locationNo, label: `${l.name}（${l.locationNo} · ${l.siteName}）` }));
    }
    if (diffDim === "SCENE") {
      return [...new Set((sitesQ.data?.list ?? []).map((s) => s.sceneType))].map((sc) => ({ value: sc, label: sc }));
    }
    return (sitesQ.data?.list ?? []).map((s) => ({ value: s.siteNo, label: `${s.name}（${s.siteNo} · ${s.sceneType}）` }));
  }, [diffDim, sitesQ.data, locationsQ.data]);
  // 冗余列是派生值，不给输入框——把派生结果作为下拉的说明文字回显，让人看见「选目标即定其余」。
  const targetHint = useMemo(() => {
    if (diffDim === "LOCATION") {
      const l = (locationsQ.data?.list ?? []).find((x) => x.locationNo === diffForm?.matchRef);
      return l ? `站点「${l.siteName}」与场景随点位带出，不单独填` : "站点与场景由所选点位带出，不单独填";
    }
    if (diffDim === "SCENE") return "命中该场景的全部站点；场景取自站点档案里真实出现过的值";
    const s = (sitesQ.data?.list ?? []).find((x) => x.siteNo === diffForm?.matchRef);
    return s ? `场景「${s.sceneType}」与展示名「${s.name}」由站点带出，不单独填` : "场景与展示名由所选站点带出，不单独填";
  }, [diffDim, sitesQ.data, locationsQ.data, diffForm?.matchRef]);

  const savePlan = useMutation({
    mutationFn: (v: Partial<PricePlan>) => api.savePricePlan(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["priceplans"] }); notify.success(t("common.success")); setPlanForm(null); },
  });
  const saveDiff = useMutation({
    mutationFn: (v: Partial<PricingDiff>) => api.savePricingDiff(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["pricingdiffs"] }); notify.success(t("common.success")); setDiffForm(null); },
  });
  const saveSchedule = useMutation({
    // 结构化字段只活在表单里：提交前折叠成 period 一列，草稿字段一个都不往接口带。
    mutationFn: (v: ScheduleDraft) => api.savePricingSchedule({
      ruleNo: v.ruleNo, name: v.name, multiplier: v.multiplier, active: v.active,
      period: formatPeriod(specOf(v)),
    }),
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

  // 时刻区间只填一半会被 formatPeriod 静默丢掉，那是最难查的一类「保存了但没生效」——提交前先挡住。
  const submitSchedule = (d: ScheduleDraft) => {
    const spec = specOf(d);
    if (spec.kind === "RANGE" && !!spec.from !== !!spec.to) {
      notify.error("开始/结束时刻要么都填、要么都留空（都留空 = 全天）");
      return;
    }
    saveSchedule.mutate(d);
  };

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
    { header: "维度", cell: (d) => <StatusBadge map={DIFF_DIM} value={d.dimension} /> },
    { header: "场景", cell: (d) => <Badge tone="outline">{d.scene}</Badge> },
    // 匹配值跟着展示名一起出：改名后名字会变，能对上的只有号；SCENE 维无实体名，说清覆盖面
    { header: "目标", cell: (d) => d.dimension === "SCENE"
      ? <span className="text-muted-foreground">该场景全部站点</span>
      : <span>{d.locationName} <span className="text-muted-foreground">{d.matchRef}</span></span> },
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
    { header: t("common.actions"), cell: (s) => canEdit ? <Button size="sm" variant="outline" onClick={() => setScheduleForm(draftOf(s))}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span> },
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
    { header: "维度", value: (d) => PRICING_DIMENSION_LABEL[d.dimension] },
    { header: "场景", value: (d) => d.scene },
    { header: "匹配值", value: (d) => d.matchRef },
    { header: "目标", value: (d) => (d.dimension === "SCENE" ? "该场景全部站点" : `${d.locationName} ${d.matchRef}`) },
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
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "templates" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索模板名称 / 适用"
            onExport={exportIf(exportPlans, plans.data?.list?.length)}
            onAdd={canEdit ? () => setPlanForm({ scope: "默认", freeMinutes: 5, unitMinutes: 30, unitPrice: 3, capDaily: 30, buyoutPrice: 199, currency: "AED", status: "ACTIVE" }) : undefined}
            addLabel="新增计费模板"
          >
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
          </Toolbar>
          <DataTable
            rowKey={(p: PricePlan) => p.planNo}
            columns={planCols}
            rows={plans.data?.list}
            loading={plans.isLoading} error={plans.error} onRetry={plans.refetch}
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
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索规则号 / 匹配值 / 场景 / 名称"
            onExport={exportIf(exportDiffs, diffs.data?.list?.length)}
            onAdd={canEdit ? () => setDiffForm({ dimension: "SITE", matchRef: "", freeMinutes: 5, unitPrice: 3, capDaily: 30, priority: 10, currency: "AED" }) : undefined}
            addLabel="新增差异化规则"
          />
          {/* 站点列表拉不到（接口挂了/无数据）时三个维度的下拉都是空的，说清楚原因，别让人对着空下拉猜 */}
          {canEdit && sitesQ.isSuccess && (sitesQ.data?.list ?? []).length === 0 && (
            <Notice>没有可选站点——差异化规则必须挂在真实站点/点位/场景上，请先在「站点与点位」建站点</Notice>
          )}
          <DataTable rowKey={(d: PricingDiff) => d.ruleNo} columns={diffCols} rows={diffs.data?.list} loading={diffs.isLoading} error={diffs.error} onRetry={diffs.refetch}
            empty="暂无差异化规则——未配置时全部点位走计费模板，可点「新增差异化规则」为机场/医院等场景单独定价" />
        </>
      )}

      {tab === "schedule" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索规则号 / 名称 / 时段"
            onExport={exportIf(exportSchedules, schedules.data?.list?.length)}
            onAdd={canEdit ? () => setScheduleForm({ name: "", multiplier: 1.5, active: true, kind: "RANGE", days: "", from: "", to: "", expr: "" }) : undefined}
            addLabel="新增活动/时段价"
          />
          <DataTable rowKey={(s: PricingSchedule) => s.ruleNo} columns={scheduleCols} rows={schedules.data?.list} loading={schedules.isLoading} error={schedules.error} onRetry={schedules.refetch}
            empty="暂无活动/时段价——未配置时不做时段加价，可点「新增活动/时段价」设节假日或高峰倍率" />
        </>
      )}

      {cur.data && <Pagination page={paging.page} size={paging.size} total={cur.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

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
        fields={diffFields(diffDim, targetOptions, targetHint)}
        value={(diffForm ?? {}) as Record<string, unknown>}
        // 切维度必须清空已选目标——站点号塞进点位维就是悬空引用，mock 层会拒，但别让人到提交才发现
        onChange={(v) => {
          const next = v as Partial<PricingDiff>;
          setDiffForm((prev) => (prev && next.dimension !== prev.dimension ? { ...next, matchRef: "" } : next));
        }}
        onSubmit={() => diffForm && saveDiff.mutate(diffForm)}
        submitting={saveDiff.isPending}
      />

      <FormDrawer
        open={!!scheduleForm}
        onOpenChange={(o) => !o && setScheduleForm(null)}
        titleNew="新增活动/时段价"
        titleEdit={`编辑活动/时段价 ${scheduleForm?.ruleNo ?? ""}`}
        isEdit={!!scheduleForm?.ruleNo}
        fields={scheduleFields(specOf(scheduleForm))}
        value={(scheduleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setScheduleForm(v as ScheduleDraft)}
        onSubmit={() => scheduleForm && submitSchedule(scheduleForm)}
        submitting={saveSchedule.isPending}
      />

      {dialog}
    </div>
  );
}

export default function PricingPage() {
  return <Suspense fallback={null}><PricingInner /></Suspense>;
}
