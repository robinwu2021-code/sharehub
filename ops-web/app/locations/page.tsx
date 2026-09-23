"use client";

// 点位管理 · 站点坪效（属 运营管理 › 场站管理）。
//
// 2026-09-23 第三步：本页从原先的七页签「站点与点位」瘦身而来 —— 场地方 / 进场合同 /
// BD 拓展 CRM / 门店 Onboarding / 门店生命周期 五项搬去了 `/venues`（属「场地方与拓展」L1）。
// 拆页的硬原因：`findActiveSection` 按**路径前缀**定归属，一个 URL 只能属于一个 L1；
// 两拨东西分属两个 L1，就必须有两个 URL，否则面包屑与 Rail 高亮必错一边。
//
// 点位不单独占菜单的那条路走不通：站点详情抽屉里能维护本站点的点位，
// 但**跨站点批量看/改点位**只有这里能做，所以它留着。
import { Suspense, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Input, Select } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FilterSelect } from "@/components/ui/filter-select";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod } from "@/lib/types";
import type { SitePoint, SiteAnalysis, PageResult } from "@/lib/types";

const TAB_KEYS = ["points", "analysis"] as const;

/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;

function LocationsInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setShowArchived(false); };
  const tabs = useNavTabs("/locations", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const [keyword, setKeyword] = useState("");
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位。
  const [showArchived, setShowArchived] = useState(false);
  // 站点坪效周期。复用报表域的 REPORT_PERIODS/缺省值 —— 自己拼一套 label，
  // 「近 30 日」在坪效页和点位报表页就会是两个窗口。
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  const [pointForm, setPointForm] = useState<Partial<SitePoint> | null>(null);

  // 点位的「所属站点」下拉：一次拉全量，下拉不分页——分页的下拉会让人以为「我的站点不见了」。
  const sitesQ = useQuery({ queryKey: ["sites-dict"], queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }) });

  const q = useQuery<PageResult<SitePoint | SiteAnalysis>>({
    // showArchived / period 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["loc", tab, paging.page, paging.size, keyword, showArchived, period],
    queryFn: () =>
      tab === "points"
        ? api.listLocations({ page: paging.page, size: paging.size, keyword, showArchived })
        : api.listSiteAnalysis({ page: paging.page, size: paging.size, keyword, period }),
    placeholderData: keepPreviousData,
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["loc"] });
  const savePoint = useMutation({
    mutationFn: (l: Partial<SitePoint>) => api.savePoint(l),
    onSuccess: () => { invalidate(); setPointForm(null); },
  });
  const archivePoint = useMutation({ mutationFn: (no: string) => api.archivePoint(no), onSuccess: () => { invalidate(); notify.success("已归档"); } });
  const unarchivePoint = useMutation({ mutationFn: (no: string) => api.unarchivePoint(no), onSuccess: () => { invalidate(); notify.success("已恢复"); } });

  const onSearch = (v: string) => { setKeyword(v); paging.reset(); };
  const archivedToggle = (
    <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
  );
  // 归档时间列只在「显示已归档」打开时插入；位置固定在操作列之前（§3.2 操作列固定最右）。
  function archivedCols<T extends { archivedAt: string | null }>(): Column<T>[] {
    return showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];
  }

  const pointCols: Column<SitePoint>[] = [
    { header: "点位号", cell: (l) => <span className="txt-strong tabular-nums">{l.locationNo}</span> },
    { header: "名称", cell: (l) => l.name },
    { header: "所属站点", cell: (l) => <span className="text-muted-foreground">{l.siteName}</span> },
    { header: "位置", cell: (l) => l.spotDesc },
    { header: "设备数", className: "text-right", cell: (l) => <span className="tabular-nums">{l.cabinetCount}</span> },
    { header: "状态", cell: (l) => l.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">暂停</Badge> },
    ...archivedCols<SitePoint>(),
    {
      header: "操作",
      cell: (l) => (
        <ArchiveActions
          archived={!!l.archivedAt}
          canWrite={allow("location:poi:update")}
          actions={<Button size="sm" variant="outline" onClick={() => setPointForm(l)}>编辑</Button>}
          // 点位不属于主数据核心（隶属站点），不要求手输编号
          onArchive={async () => { if (await confirm(archiveConfirm("点位", l.locationNo))) archivePoint.mutate(l.locationNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("点位", l.locationNo))) unarchivePoint.mutate(l.locationNo); }}
        />
      ),
    },
  ];

  const analysisCols: Column<SiteAnalysis>[] = [
    { header: "站点号", cell: (a) => <span className="txt-strong tabular-nums">{a.siteNo}</span> },
    { header: "站点名称", cell: (a) => a.siteName },
    { header: "营收", className: "text-right", cell: (a) => <span className="tabular-nums">{money(a.revenue, a.currency)}</span> },
    { header: "订单数", className: "text-right", cell: (a) => <span className="tabular-nums">{Math.round(a.orders)}</span> },
    { header: "翻台（次/日）", className: "text-right", cell: (a) => <span className="tabular-nums">{a.turnover.toFixed(1)}</span> },
    { header: "回本天数", className: "text-right", cell: (a) => <span className="tabular-nums">{Math.round(a.paybackDays)}</span> },
    { header: "机柜数", className: "text-right", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
  ];

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
  function pageRows<T>(): T[] { return (q.data?.list ?? []) as T[]; }
  function archivedCsv<T extends { archivedAt: string | null }>(): CsvColumn<T>[] {
    return showArchived ? [{ header: "归档时间", value: (r) => (r.archivedAt ? fmtTime(r.archivedAt) : "") }] : [];
  }
  const onExport = () => {
    if (tab === "points") {
      exportCsv<SitePoint>("点位", [
        { header: "点位号", value: (l) => l.locationNo },
        { header: "名称", value: (l) => l.name },
        { header: "所属站点", value: (l) => l.siteName },
        { header: "位置", value: (l) => l.spotDesc },
        { header: "设备数", value: (l) => l.cabinetCount },
        { header: "状态", value: (l) => (l.status === "ACTIVE" ? "启用" : "暂停") },
        ...archivedCsv<SitePoint>(),
      ], pageRows<SitePoint>());
    } else {
      exportCsv<SiteAnalysis>(`站点坪效-${periodLabel(period)}`, [
        { header: "站点号", value: (a) => a.siteNo },
        { header: "站点名称", value: (a) => a.siteName },
        { header: "营收", value: (a) => money(a.revenue, a.currency) },
        { header: "订单数", value: (a) => Math.round(a.orders) },
        { header: "翻台（次/日）", value: (a) => a.turnover.toFixed(1) },
        { header: "回本天数", value: (a) => Math.round(a.paybackDays) },
        { header: "机柜数", value: (a) => a.cabinetCount },
      ], pageRows<SiteAnalysis>());
    }
  };

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "points" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索点位号 / 名称" onExport={onExport}
          onAdd={allow("location:poi:create") ? () => setPointForm({ status: "ACTIVE" }) : undefined} addLabel="新增点位">
          {archivedToggle}
        </Toolbar>
      )}
      {tab === "analysis" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称" onExport={onExport}>
          <FilterSelect
            value={period}
            onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }}
            options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
            aria-label="按统计周期筛选"
          />
        </Toolbar>
      )}

      {tab === "points" && <DataTable rowKey={(l: SitePoint) => l.locationNo} columns={pointCols} rows={q.data?.list as SitePoint[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的点位——换个关键词，或先「新增点位」" : "没有在用的点位——点位隶属站点，先建站点再在此新增，或打开「显示已归档」查看已归档点位"} />}
      {tab === "analysis" && <DataTable rowKey={(a: SiteAnalysis) => a.siteNo} columns={analysisCols} rows={q.data?.list as SiteAnalysis[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={`${periodLabel(period)}内没有坪效数据——统计截至昨日（T+1），新站点需先产生订单；可换更长的周期再看`} />}
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      {/* 点位 新增/编辑 */}
      <Drawer
        open={!!pointForm}
        onOpenChange={(o) => !o && setPointForm(null)}
        title={pointForm?.locationNo ? `编辑点位 ${pointForm.locationNo}` : "新增点位"}
        desc="站点内的具体投放位置"
        footer={<><Button variant="outline" onClick={() => setPointForm(null)}>取消</Button><Button disabled={savePoint.isPending} onClick={() => pointForm && savePoint.mutate(pointForm)}>保存</Button></>}
      >
        {pointForm && (<>
          <Field label="点位名称"><Input value={pointForm.name ?? ""} onChange={(e) => setPointForm({ ...pointForm, name: e.target.value })} /></Field>
          {/* 站点从列表选：点位是归属链（站点→点位→机柜）的一环，填错等于设备挂到别的站点 */}
          <Field label="所属站点">
            <Select className="w-full" value={pointForm.siteNo ?? ""} onChange={(e) => {
              const x = (sitesQ.data?.list ?? []).find((y) => y.siteNo === e.target.value);
              setPointForm({ ...pointForm, siteNo: e.target.value, siteName: x?.name ?? "" });
            }}>
              <option value="">请选择站点</option>
              {(sitesQ.data?.list ?? []).map((x) => (
                <option key={x.siteNo} value={x.siteNo}>{x.name}（{x.siteNo}）</option>
              ))}
            </Select>
          </Field>
          <Field label="位置描述"><Input value={pointForm.spotDesc ?? ""} onChange={(e) => setPointForm({ ...pointForm, spotDesc: e.target.value })} placeholder="近扶梯" /></Field>
          <Field label="状态">
            <Select className="w-full" value={pointForm.status ?? "ACTIVE"} onChange={(e) => setPointForm({ ...pointForm, status: e.target.value as SitePoint["status"] })}>
              <option value="ACTIVE">启用</option><option value="PAUSED">暂停</option>
            </Select>
          </Field>
        </>)}
      </Drawer>

      {dialog}
    </div>
  );
}

export default function LocationsPage() {
  // 读 useSearchParams 的组件在静态导出下必须包 Suspense
  return <Suspense fallback={null}><LocationsInner /></Suspense>;
}
