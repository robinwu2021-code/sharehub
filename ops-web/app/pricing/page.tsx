"use client";

import { Suspense, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
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
import { exportCsv } from "@/lib/export-csv";
import { money } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { PricingDiff, PricingDimension } from "@/lib/types";
import { PRICING_DIMENSION_LABEL } from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
//
// 2026-09-23 移除 "templates" 与 "schedule"：它们与「运营管理 › 收费方案」的两个页签
// 调同一组 API（listPricePlans/savePricePlan/archivePricePlan、listPricingSchedules/
// savePricingSchedule），是同一张表的两个维护入口。保留后者 —— 它多了试算与
// 「本方案有哪些待执行调价」，且 ADR-028 的「适用范围」要落在那边。
// 本页只剩「差异化定价」：它按经营链条方案 P1 会并入收费方案的适用范围后整页下线，
// 在那之前先留着，不在替代品建好前删掉唯一的差异化取价入口。
const TAB_KEYS = ["diff"] as const;

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

function PricingInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setKeyword(""); };
  const tabs = useNavTabs("/pricing", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const [keyword, setKeyword] = useState("");
  const [diffForm, setDiffForm] = useState<Partial<PricingDiff> | null>(null);
  const canEdit = allow("pricing:rule:update");

  const diffs = useQuery({
    queryKey: ["pricingdiffs", paging.page, paging.size, keyword],
    queryFn: () => api.listPricingDiffs({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "diff",
  });
  // 目标下拉的选项源：差异化规则必须指向真实实体，按维度读各自的表。
  // size=200 一次拉全——下拉不分页，分页的下拉会让人以为「我的站点不见了」。
  const sitesQ = useQuery({
    queryKey: ["pricing-sites"],
    queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }),
    enabled: tab === "diff",
  });
  const locationsQ = useQuery({
    queryKey: ["pricing-locations"],
    queryFn: () => api.listLocations({ page: 1, size: UNPAGED_SIZE }),
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

  const saveDiff = useMutation({
    mutationFn: (v: Partial<PricingDiff>) => api.savePricingDiff(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["pricingdiffs"] }); notify.success(t("common.success")); setDiffForm(null); },
  });
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

  const cur = diffs;

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
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
  /** 无数据时不给导出按钮：导出一个空 CSV 只会让人以为是导出坏了。 */
  const exportIf = (fn: () => void, n?: number) => (n ? fn : undefined);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

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

      {cur.data && <Pagination page={paging.page} size={paging.size} total={cur.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

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
    </div>
  );
}

export default function PricingPage() {
  return <Suspense fallback={null}><PricingInner /></Suspense>;
}
