"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { Input, Select } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Site, SitePoint, Venue, Contract, Lead, SiteAnalysis, VenueOnboarding, SiteLifecycle, PageResult, Region } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "sites", label: "站点" }, { key: "points", label: "点位" },
  { key: "venues", label: "场地方" }, { key: "contracts", label: "合同", phase: 2 as const },
  { key: "onboarding", label: "门店 Onboarding", phase: 2 as const },
  { key: "crm", label: "BD 拓展 CRM", phase: 3 as const }, { key: "analysis", label: "站点坪效", phase: 3 as const },
  { key: "lifecycle", label: "门店生命周期", phase: 3 as const },
];
const LEAD_STAGE: Record<Lead["stage"], { label: string; tone: "muted" | "outline" | "default" | "warning" | "success" | "danger" }> = {
  NEW: { label: "新线索", tone: "muted" },
  CONTACTED: { label: "已接触", tone: "outline" },
  NEGOTIATING: { label: "洽谈中", tone: "warning" },
  SIGNED: { label: "已签约", tone: "success" },
  LOST: { label: "已流失", tone: "danger" },
};
const SCENES = ["商场", "机场", "餐饮", "地铁", "写字楼"];
const LEAD_STAGE_OPTIONS = [
  { value: "NEW", label: "新线索" },
  { value: "CONTACTED", label: "已接触" },
  { value: "NEGOTIATING", label: "洽谈中" },
  { value: "SIGNED", label: "已签约" },
  { value: "LOST", label: "已流失" },
];
const VENUE_FIELDS: FieldDef[] = [
  { key: "venueNo", label: "编号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "name", label: "名称", placeholder: "Dubai Mall" },
  { key: "contact", label: "联系方式", placeholder: "姓名 / 电话" },
  { key: "industry", label: "行业", placeholder: "购物中心" },
  { key: "locationCount", label: "站点数", type: "number" },
];
const CONTRACT_FIELDS: FieldDef[] = [
  { key: "contractNo", label: "合同号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "venueName", label: "场地方", placeholder: "Dubai Mall" },
  { key: "siteName", label: "站点", placeholder: "Dubai Mall L1" },
  { key: "shareRate", label: "分成比例（0~1）", type: "number" },
  { key: "entryFee", label: "进场费", type: "number" },
  { key: "startAt", label: "生效时间", placeholder: "2026-01-01" },
  { key: "endAt", label: "到期时间", placeholder: "2027-01-01" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "有效" }, { value: "EXPIRED", label: "过期" }] },
];
const LEAD_FIELDS: FieldDef[] = [
  { key: "leadNo", label: "线索号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "venueName", label: "场地名称", placeholder: "某商场" },
  { key: "contact", label: "联系人", placeholder: "姓名 / 电话" },
  { key: "stage", label: "阶段", type: "select", options: LEAD_STAGE_OPTIONS },
  { key: "owner", label: "负责人", placeholder: "BD 姓名" },
  { key: "expectSites", label: "预计站点数", type: "number" },
];
const ONBOARDING_FIELDS: FieldDef[] = [
  { key: "onboardingNo", label: "申请号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "venueName", label: "场地名称", placeholder: "Al Barsha Mall" },
  { key: "contact", label: "联系人", placeholder: "姓名 + 电话" },
  { key: "industry", label: "行业", placeholder: "购物中心" },
  { key: "status", label: "审核状态", type: "select", options: [{ value: "PENDING", label: "待审核" }, { value: "APPROVED", label: "已通过" }, { value: "REJECTED", label: "已驳回" }] },
  { key: "reviewNote", label: "审核备注", placeholder: "通过/驳回原因" },
];

function LocationsInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const sp = useSearchParams();
  const { confirm, dialog } = useConfirm();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "sites");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位，避免在合同页残留一个看不见的过滤态。
  const [showArchived, setShowArchived] = useState(false);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);
  const [siteForm, setSiteForm] = useState<Partial<Site> | null>(null);
  const [pointForm, setPointForm] = useState<Partial<SitePoint> | null>(null);
  const [venueForm, setVenueForm] = useState<Partial<Venue> | null>(null);
  const [contractForm, setContractForm] = useState<Partial<Contract> | null>(null);
  const [leadForm, setLeadForm] = useState<Partial<Lead> | null>(null);
  const [onboardingForm, setOnboardingForm] = useState<Partial<VenueOnboarding> | null>(null);

  const canVenue = allow("location:venue:update");
  const canContract = allow("location:contract:update");
  const canLead = allow("location:lead:update");

  // 站点表单的区域下拉数据源（system 域字典）
  const regionsQ = useQuery({ queryKey: ["regions-dict"], queryFn: () => api.listRegions({ page: 1, size: 100 }) });
  const q = useQuery<PageResult<Site | SitePoint | Venue | Contract | Lead | SiteAnalysis | VenueOnboarding | SiteLifecycle>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["place", tab, page, keyword, showArchived],
    queryFn: () =>
      tab === "sites" ? api.listSites({ page, size: SIZE, keyword, showArchived })
      : tab === "points" ? api.listLocations({ page, size: SIZE, keyword, showArchived })
      : tab === "venues" ? api.listVenues({ page, size: SIZE, keyword, showArchived })
      : tab === "crm" ? api.listLeads({ page, size: SIZE, keyword })
      : tab === "analysis" ? api.listSiteAnalysis({ page, size: SIZE, keyword })
      : tab === "onboarding" ? api.listVenueOnboardings({ page, size: SIZE, keyword })
      : tab === "lifecycle" ? api.listSiteLifecycles({ page, size: SIZE, keyword })
      : api.listContracts({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });

  const saveSite = useMutation({
    mutationFn: (s: Partial<Site>) => api.saveSite(s),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "sites"] }); setSiteForm(null); },
  });
  const savePoint = useMutation({
    mutationFn: (l: Partial<SitePoint>) => api.savePoint(l),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "points"] }); setPointForm(null); },
  });
  const saveVenue = useMutation({
    mutationFn: (v: Partial<Venue>) => api.saveVenue(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "venues"] }); notify.success("保存成功"); setVenueForm(null); },
  });
  const saveContract = useMutation({
    mutationFn: (c: Partial<Contract>) => api.saveContract(c),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "contracts"] }); notify.success("保存成功"); setContractForm(null); },
  });
  const saveLead = useMutation({
    mutationFn: (l: Partial<Lead>) => api.saveLead(l),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "crm"] }); notify.success("保存成功"); setLeadForm(null); },
  });
  const saveOnboarding = useMutation({
    mutationFn: (o: Partial<VenueOnboarding>) => api.saveVenueOnboarding(o),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "onboarding"] }); notify.success("保存成功"); setOnboardingForm(null); },
  });

  // 归档 / 恢复（G1 软删除）。错误由全局 MutationCache 接管，页面不重复 catch。
  const invalidatePlace = () => qc.invalidateQueries({ queryKey: ["place"] });
  const archiveSite = useMutation({ mutationFn: (no: string) => api.archiveSite(no), onSuccess: () => { invalidatePlace(); notify.success("已归档"); } });
  const unarchiveSite = useMutation({ mutationFn: (no: string) => api.unarchiveSite(no), onSuccess: () => { invalidatePlace(); notify.success("已恢复"); } });
  const archivePoint = useMutation({ mutationFn: (no: string) => api.archivePoint(no), onSuccess: () => { invalidatePlace(); notify.success("已归档"); } });
  const unarchivePoint = useMutation({ mutationFn: (no: string) => api.unarchivePoint(no), onSuccess: () => { invalidatePlace(); notify.success("已恢复"); } });
  const archiveVenue = useMutation({ mutationFn: (no: string) => api.archiveVenue(no), onSuccess: () => { invalidatePlace(); notify.success("已归档"); } });
  const unarchiveVenue = useMutation({ mutationFn: (no: string) => api.unarchiveVenue(no), onSuccess: () => { invalidatePlace(); notify.success("已恢复"); } });

  /** 归档时间列：只在「显示已归档」打开时出现，默认视图里整列都是 `-` 属于噪音。 */
  function archivedCols<T extends { archivedAt: string | null }>(): Column<T>[] {
    return showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];
  }

  const siteCols: Column<Site>[] = [
    { header: "站点号", cell: (s) => <span className="font-medium">{s.siteNo}</span> },
    { header: "名称", cell: (s) => s.name },
    { header: "场地方", cell: (s) => <span className="text-muted-foreground">{s.venueName}</span> },
    { header: "区域", cell: (s) => <span title={s.regionId}>{s.regionName}</span> },
    { header: "归属", cell: (s) => s.agentNo ? <Badge tone="outline">代理 {s.agentNo}</Badge> : <Badge tone="muted">平台直营</Badge> },
    { header: "点位/设备", cell: (s) => <span className="tabular-nums">{s.pointCount} / {s.cabinetCount}</span> },
    { header: "状态", cell: (s) => s.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">暂停</Badge> },
    ...archivedCols<Site>(),
    {
      header: "操作",
      cell: (s) => (
        <ArchiveActions
          archived={!!s.archivedAt}
          canWrite={allow("location:poi:update")}
          actions={<Button size="sm" variant="outline" onClick={() => setSiteForm(s)}>编辑</Button>}
          // 站点是主数据：要求手输站点号确认，避免误点
          onArchive={async () => { if (await confirm(archiveConfirm("站点", s.siteNo, s.siteNo))) archiveSite.mutate(s.siteNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("站点", s.siteNo))) unarchiveSite.mutate(s.siteNo); }}
        />
      ),
    },
  ];
  const pointCols: Column<SitePoint>[] = [
    { header: "点位号", cell: (l) => <span className="font-medium">{l.locationNo}</span> },
    { header: "名称", cell: (l) => l.name },
    { header: "所属站点", cell: (l) => <span className="text-muted-foreground">{l.siteName}</span> },
    { header: "位置", cell: (l) => l.spotDesc },
    { header: "设备数", cell: (l) => <span className="tabular-nums">{l.cabinetCount}</span> },
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
  const venueCols: Column<Venue>[] = [
    { header: "编号", cell: (v) => <span className="font-medium">{v.venueNo}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "联系方式", cell: (v) => <span className="text-muted-foreground">{v.contact}</span> },
    { header: "行业", cell: (v) => v.industry },
    { header: "站点数", cell: (v) => <span className="tabular-nums">{v.locationCount}</span> },
    ...archivedCols<Venue>(),
    {
      header: "操作",
      cell: (v) => (
        <ArchiveActions
          archived={!!v.archivedAt}
          canWrite={canVenue}
          actions={<Button size="sm" variant="outline" onClick={() => setVenueForm(v)}>编辑</Button>}
          // 场地方是主数据：要求手输编号确认
          onArchive={async () => { if (await confirm(archiveConfirm("场地方", v.venueNo, v.venueNo))) archiveVenue.mutate(v.venueNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("场地方", v.venueNo))) unarchiveVenue.mutate(v.venueNo); }}
        />
      ),
    },
  ];
  const ctCols: Column<Contract>[] = [
    { header: "合同号", cell: (c) => <span className="font-medium">{c.contractNo}</span> },
    { header: "场地方", cell: (c) => c.venueName },
    { header: "站点", cell: (c) => <span className="text-muted-foreground">{c.siteName}</span> },
    { header: "分成", cell: (c) => `${(c.shareRate * 100).toFixed(0)}%` },
    { header: "进场费", cell: (c) => c.entryFee },
    { header: "到期", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.endAt)}</span> },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">有效</Badge> : <Badge tone="danger">过期</Badge> },
    { header: "操作", cell: (c) => canContract ? <Button size="sm" variant="outline" onClick={() => setContractForm(c)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const leadCols: Column<Lead>[] = [
    { header: "线索号", cell: (l) => <span className="font-medium">{l.leadNo}</span> },
    { header: "场地名称", cell: (l) => l.venueName },
    { header: "联系人", cell: (l) => <span className="text-muted-foreground">{l.contact}</span> },
    { header: "阶段", cell: (l) => <Badge tone={LEAD_STAGE[l.stage].tone}>{LEAD_STAGE[l.stage].label}</Badge> },
    { header: "负责人", cell: (l) => l.owner },
    { header: "预计站点数", cell: (l) => <span className="tabular-nums">{l.expectSites}</span> },
    { header: "更新时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.updatedAt)}</span> },
    { header: "操作", cell: (l) => canLead ? <Button size="sm" variant="outline" onClick={() => setLeadForm(l)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const analysisCols: Column<SiteAnalysis>[] = [
    { header: "站点号", cell: (a) => <span className="font-medium">{a.siteNo}</span> },
    { header: "站点名称", cell: (a) => a.siteName },
    { header: "营收", cell: (a) => <span className="tabular-nums">{money(a.revenue, a.currency)}</span> },
    { header: "订单数", cell: (a) => <span className="tabular-nums">{Math.round(a.orders)}</span> },
    { header: "翻台（次/日）", cell: (a) => <span className="tabular-nums">{a.turnover.toFixed(1)}</span> },
    { header: "回本天数", cell: (a) => <span className="tabular-nums">{Math.round(a.paybackDays)}</span> },
    { header: "机柜数", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
  ];
  const OB_STATUS: Record<VenueOnboarding["status"], { label: string; tone: "warning" | "success" | "danger" }> = {
    PENDING: { label: "待审核", tone: "warning" },
    APPROVED: { label: "已通过", tone: "success" },
    REJECTED: { label: "已驳回", tone: "danger" },
  };
  const onboardingCols: Column<VenueOnboarding>[] = [
    { header: "申请号", cell: (o) => <span className="font-medium">{o.onboardingNo}</span> },
    { header: "场地名称", cell: (o) => o.venueName },
    { header: "联系人", cell: (o) => <span className="text-muted-foreground">{o.contact}</span> },
    { header: "行业", cell: (o) => o.industry },
    { header: "申请时间", cell: (o) => <span className="text-muted-foreground">{fmtTime(o.requestedAt)}</span> },
    { header: "审核状态", cell: (o) => <Badge tone={OB_STATUS[o.status].tone}>{OB_STATUS[o.status].label}</Badge> },
    { header: "备注", cell: (o) => <span className="text-muted-foreground">{o.reviewNote ?? "-"}</span> },
    { header: "操作", cell: (o) => canVenue ? <Button size="sm" variant="outline" onClick={() => setOnboardingForm(o)}>审核</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const LC_STAGE: Record<SiteLifecycle["stage"], { label: string; tone: "muted" | "outline" | "warning" | "success" | "danger" | "default" }> = {
    PROSPECTING: { label: "潜在", tone: "muted" },
    SIGNED: { label: "已签约", tone: "outline" },
    LIVE: { label: "上线", tone: "warning" },
    ACTIVE: { label: "运营中", tone: "success" },
    CHURNED: { label: "流失", tone: "danger" },
    CLOSED: { label: "关闭", tone: "muted" },
  };
  const lifecycleCols: Column<SiteLifecycle>[] = [
    { header: "站点号", cell: (l) => <span className="font-medium">{l.siteNo}</span> },
    { header: "站点名称", cell: (l) => l.siteName },
    { header: "阶段", cell: (l) => <Badge tone={LC_STAGE[l.stage].tone}>{LC_STAGE[l.stage].label}</Badge> },
    { header: "阶段更新", cell: (l) => <span className="text-muted-foreground">{l.stageAt}</span> },
    { header: "负责人", cell: (l) => l.owner },
    { header: "GMV (LTM)", cell: (l) => <span className="tabular-nums">{money(l.gmvLtm, l.currency)}</span> },
  ];

  const onSearch = (v: string) => { setKeyword(v); setPage(1); };

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
  function pageRows<T>(): T[] { return (q.data?.list ?? []) as T[]; }
  function archivedCsv<T extends { archivedAt: string | null }>(): CsvColumn<T>[] {
    return showArchived ? [{ header: "归档时间", value: (r) => (r.archivedAt ? fmtTime(r.archivedAt) : "") }] : [];
  }
  function exportCurrent() {
    if (tab === "sites") {
      exportCsv<Site>("站点", [
        { header: "站点号", value: (s) => s.siteNo },
        { header: "名称", value: (s) => s.name },
        { header: "场地方", value: (s) => s.venueName },
        { header: "区域", value: (s) => s.regionName },
        { header: "归属", value: (s) => (s.agentNo ? `代理 ${s.agentNo}` : "平台直营") },
        { header: "点位/设备", value: (s) => `${s.pointCount} / ${s.cabinetCount}` },
        { header: "状态", value: (s) => (s.status === "ACTIVE" ? "启用" : "暂停") },
        ...archivedCsv<Site>(),
      ], pageRows<Site>());
    } else if (tab === "points") {
      exportCsv<SitePoint>("点位", [
        { header: "点位号", value: (l) => l.locationNo },
        { header: "名称", value: (l) => l.name },
        { header: "所属站点", value: (l) => l.siteName },
        { header: "位置", value: (l) => l.spotDesc },
        { header: "设备数", value: (l) => l.cabinetCount },
        { header: "状态", value: (l) => (l.status === "ACTIVE" ? "启用" : "暂停") },
        ...archivedCsv<SitePoint>(),
      ], pageRows<SitePoint>());
    } else if (tab === "venues") {
      exportCsv<Venue>("场地方", [
        { header: "编号", value: (v) => v.venueNo },
        { header: "名称", value: (v) => v.name },
        { header: "联系方式", value: (v) => v.contact },
        { header: "行业", value: (v) => v.industry },
        { header: "站点数", value: (v) => v.locationCount },
        ...archivedCsv<Venue>(),
      ], pageRows<Venue>());
    } else if (tab === "contracts") {
      exportCsv<Contract>("合同", [
        { header: "合同号", value: (c) => c.contractNo },
        { header: "场地方", value: (c) => c.venueName },
        { header: "站点", value: (c) => c.siteName },
        { header: "分成", value: (c) => `${(c.shareRate * 100).toFixed(0)}%` },
        { header: "进场费", value: (c) => c.entryFee },
        { header: "到期", value: (c) => fmtTime(c.endAt) },
        { header: "状态", value: (c) => (c.status === "ACTIVE" ? "有效" : "过期") },
      ], pageRows<Contract>());
    } else if (tab === "crm") {
      exportCsv<Lead>("BD 拓展 CRM", [
        { header: "线索号", value: (l) => l.leadNo },
        { header: "场地名称", value: (l) => l.venueName },
        { header: "联系人", value: (l) => l.contact },
        { header: "阶段", value: (l) => LEAD_STAGE[l.stage].label },
        { header: "负责人", value: (l) => l.owner },
        { header: "预计站点数", value: (l) => l.expectSites },
        { header: "更新时间", value: (l) => fmtTime(l.updatedAt) },
      ], pageRows<Lead>());
    } else if (tab === "analysis") {
      exportCsv<SiteAnalysis>("站点坪效", [
        { header: "站点号", value: (a) => a.siteNo },
        { header: "站点名称", value: (a) => a.siteName },
        { header: "营收", value: (a) => money(a.revenue, a.currency) },
        { header: "订单数", value: (a) => Math.round(a.orders) },
        { header: "翻台（次/日）", value: (a) => a.turnover.toFixed(1) },
        { header: "回本天数", value: (a) => Math.round(a.paybackDays) },
        { header: "机柜数", value: (a) => a.cabinetCount },
      ], pageRows<SiteAnalysis>());
    } else if (tab === "onboarding") {
      exportCsv<VenueOnboarding>("门店 Onboarding", [
        { header: "申请号", value: (o) => o.onboardingNo },
        { header: "场地名称", value: (o) => o.venueName },
        { header: "联系人", value: (o) => o.contact },
        { header: "行业", value: (o) => o.industry },
        { header: "申请时间", value: (o) => fmtTime(o.requestedAt) },
        { header: "审核状态", value: (o) => OB_STATUS[o.status].label },
        { header: "备注", value: (o) => o.reviewNote ?? "" },
      ], pageRows<VenueOnboarding>());
    } else if (tab === "lifecycle") {
      exportCsv<SiteLifecycle>("门店生命周期", [
        { header: "站点号", value: (l) => l.siteNo },
        { header: "站点名称", value: (l) => l.siteName },
        { header: "阶段", value: (l) => LC_STAGE[l.stage].label },
        { header: "阶段更新", value: (l) => l.stageAt },
        { header: "负责人", value: (l) => l.owner },
        { header: "GMV (LTM)", value: (l) => money(l.gmvLtm, l.currency) },
      ], pageRows<SiteLifecycle>());
    }
  }
  // 无数据时不给导出按钮：导出一个空 CSV 只会让人以为功能坏了
  const onExport = q.data?.list?.length ? exportCurrent : undefined;
  const archivedToggle = <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />;

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setShowArchived(false); }} />
      {tab === "sites" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称" onExport={onExport}
          onAdd={allow("location:poi:create") ? () => setSiteForm({ status: "ACTIVE", sceneType: "商场", agentNo: null }) : undefined} addLabel="新增站点">
          {archivedToggle}
        </Toolbar>
      )}
      {tab === "points" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索点位号 / 名称" onExport={onExport}
          onAdd={allow("location:poi:create") ? () => setPointForm({ status: "ACTIVE" }) : undefined} addLabel="新增点位">
          {archivedToggle}
        </Toolbar>
      )}
      {tab === "venues" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地方名称" onExport={onExport}
          onAdd={canVenue ? () => setVenueForm({ locationCount: 0 }) : undefined} addLabel="新增场地方">
          {archivedToggle}
        </Toolbar>
      )}
      {tab === "contracts" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地方 / 站点" onExport={onExport}
          onAdd={canContract ? () => setContractForm({ status: "ACTIVE", shareRate: 0.15, entryFee: 0 }) : undefined} addLabel="新增合同" />
      )}
      {tab === "crm" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索线索号 / 场地 / 负责人" onExport={onExport}
          onAdd={canLead ? () => setLeadForm({ stage: "NEW", expectSites: 1 }) : undefined} addLabel="新增线索" />
      )}
      {tab === "analysis" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称" onExport={onExport} />
      )}
      {tab === "onboarding" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地名称 / 联系人" onExport={onExport}
          onAdd={canVenue ? () => setOnboardingForm({ status: "PENDING", industry: "购物中心" }) : undefined} addLabel="新增申请" />
      )}
      {tab === "lifecycle" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称 / 负责人" onExport={onExport} />
      )}
      {tab === "sites" && <DataTable rowKey={(s: Site) => s.siteNo} columns={siteCols} rows={q.data?.list as Site[]} loading={q.isLoading} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的站点——换个关键词，或先「新增站点」建档" : "没有在用的站点——可能都已归档（打开「显示已归档」查看），或先「新增站点」建档"} />}
      {tab === "points" && <DataTable rowKey={(l: SitePoint) => l.locationNo} columns={pointCols} rows={q.data?.list as SitePoint[]} loading={q.isLoading} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的点位——换个关键词，或先「新增点位」" : "没有在用的点位——点位隶属站点，先建站点再在此新增，或打开「显示已归档」查看已归档点位"} />}
      {tab === "venues" && <DataTable rowKey={(v: Venue) => v.venueNo} columns={venueCols} rows={q.data?.list as Venue[]} loading={q.isLoading} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的场地方——换个关键词，或先「新增场地方」" : "没有在用的场地方——可能都已归档（打开「显示已归档」查看），或先「新增场地方」建档"} />}
      {tab === "contracts" && <DataTable rowKey={(c: Contract) => c.contractNo} columns={ctCols} rows={q.data?.list as Contract[]} loading={q.isLoading}
        empty="暂无合同——合同绑定「场地方 × 站点」，请先建好两者再「新增合同」" />}
      {tab === "crm" && <DataTable rowKey={(l: Lead) => l.leadNo} columns={leadCols} rows={q.data?.list as Lead[]} loading={q.isLoading}
        empty="暂无线索——BD 拓展的场地线索会出现在这里，可点「新增线索」手工录入" />}
      {tab === "analysis" && <DataTable rowKey={(a: SiteAnalysis) => a.siteNo} columns={analysisCols} rows={q.data?.list as SiteAnalysis[]} loading={q.isLoading}
        empty="暂无坪效数据——站点需先产生订单，次日汇总后才会出现在此" />}
      {tab === "onboarding" && <DataTable rowKey={(o: VenueOnboarding) => o.onboardingNo} columns={onboardingCols} rows={q.data?.list as VenueOnboarding[]} loading={q.isLoading}
        empty="暂无入驻申请——门店自助提交的申请会进入此列表待审核，也可点「新增申请」代录" />}
      {tab === "lifecycle" && <DataTable rowKey={(l: SiteLifecycle) => l.siteNo} columns={lifecycleCols} rows={q.data?.list as SiteLifecycle[]} loading={q.isLoading}
        empty="暂无生命周期记录——站点签约后自动进入跟踪，尚无签约站点时此处为空" />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      {/* 站点 新增/编辑 */}
      <Drawer
        open={!!siteForm}
        onOpenChange={(o) => !o && setSiteForm(null)}
        title={siteForm?.siteNo ? `编辑站点 ${siteForm.siteNo}` : "新增站点"}
        desc="站点=运营与归属单元；归属决定分润与数据可见"
        footer={<><Button variant="outline" onClick={() => setSiteForm(null)}>取消</Button><Button disabled={saveSite.isPending} onClick={() => siteForm && saveSite.mutate(siteForm)}>保存</Button></>}
      >
        {siteForm && (<>
          <Field label="站点名称"><Input value={siteForm.name ?? ""} onChange={(e) => setSiteForm({ ...siteForm, name: e.target.value })} /></Field>
          <Field label="场地方"><Input value={siteForm.venueName ?? ""} onChange={(e) => setSiteForm({ ...siteForm, venueName: e.target.value })} /></Field>
          {/* 区域从字典选，不能自由输入——台账 M11：原先是文本框，写进去的名字在 regions 字典里根本不存在 */}
          <Field label="区域">
            <Select className="w-full" value={siteForm.regionId ?? ""} onChange={(e) => {
              const r = (regionsQ.data?.list ?? []).find((x) => x.regionId === e.target.value);
              setSiteForm({ ...siteForm, regionId: e.target.value, regionName: r?.name ?? "" });
            }}>
              <option value="">请选择区域</option>
              {(regionsQ.data?.list ?? []).filter((r) => r.level === 3).map((r) => (
                <option key={r.regionId} value={r.regionId}>{r.name}（{r.regionId}）</option>
              ))}
            </Select>
          </Field>
          <Field label="归属代理（空=平台直营）"><Input value={siteForm.agentNo ?? ""} onChange={(e) => setSiteForm({ ...siteForm, agentNo: e.target.value || null })} placeholder="AG001" /></Field>
          <Field label="场景">
            <Select className="w-full" value={siteForm.sceneType ?? "商场"} onChange={(e) => setSiteForm({ ...siteForm, sceneType: e.target.value })}>
              {SCENES.map((s) => <option key={s} value={s}>{s}</option>)}
            </Select>
          </Field>
          <Field label="地址"><Input value={siteForm.address ?? ""} onChange={(e) => setSiteForm({ ...siteForm, address: e.target.value })} /></Field>
          <Field label="状态">
            <Select className="w-full" value={siteForm.status ?? "ACTIVE"} onChange={(e) => setSiteForm({ ...siteForm, status: e.target.value as Site["status"] })}>
              <option value="ACTIVE">启用</option><option value="PAUSED">暂停</option>
            </Select>
          </Field>
        </>)}
      </Drawer>

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
          <Field label="所属站点名"><Input value={pointForm.siteName ?? ""} onChange={(e) => setPointForm({ ...pointForm, siteName: e.target.value })} placeholder="Dubai Mall L1" /></Field>
          <Field label="位置描述"><Input value={pointForm.spotDesc ?? ""} onChange={(e) => setPointForm({ ...pointForm, spotDesc: e.target.value })} placeholder="近扶梯" /></Field>
          <Field label="状态">
            <Select className="w-full" value={pointForm.status ?? "ACTIVE"} onChange={(e) => setPointForm({ ...pointForm, status: e.target.value as SitePoint["status"] })}>
              <option value="ACTIVE">启用</option><option value="PAUSED">暂停</option>
            </Select>
          </Field>
        </>)}
      </Drawer>

      {/* 场地方 新增/编辑 */}
      <FormDrawer
        open={!!venueForm}
        onOpenChange={(o) => !o && setVenueForm(null)}
        titleNew="新增场地方"
        titleEdit={`编辑场地方 ${venueForm?.venueNo ?? ""}`}
        isEdit={!!venueForm?.venueNo}
        fields={VENUE_FIELDS}
        value={(venueForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setVenueForm(v as Partial<Venue>)}
        onSubmit={() => venueForm && saveVenue.mutate(venueForm)}
        submitting={saveVenue.isPending}
      />

      {/* 合同 新增/编辑 */}
      <FormDrawer
        open={!!contractForm}
        onOpenChange={(o) => !o && setContractForm(null)}
        titleNew="新增合同"
        titleEdit={`编辑合同 ${contractForm?.contractNo ?? ""}`}
        isEdit={!!contractForm?.contractNo}
        fields={CONTRACT_FIELDS}
        value={(contractForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setContractForm(v as Partial<Contract>)}
        onSubmit={() => contractForm && saveContract.mutate(contractForm)}
        submitting={saveContract.isPending}
      />

      {/* 门店 Onboarding 审核 */}
      <FormDrawer
        open={!!onboardingForm}
        onOpenChange={(o) => !o && setOnboardingForm(null)}
        titleNew="新增 Onboarding 申请"
        titleEdit={`审核申请 ${onboardingForm?.onboardingNo ?? ""}`}
        isEdit={!!onboardingForm?.onboardingNo}
        fields={ONBOARDING_FIELDS}
        value={(onboardingForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setOnboardingForm(v as Partial<VenueOnboarding>)}
        onSubmit={() => onboardingForm && saveOnboarding.mutate(onboardingForm)}
        submitting={saveOnboarding.isPending}
      />

      {/* BD 线索 新增/编辑 */}
      <FormDrawer
        open={!!leadForm}
        onOpenChange={(o) => !o && setLeadForm(null)}
        titleNew="新增线索"
        titleEdit={`编辑线索 ${leadForm?.leadNo ?? ""}`}
        isEdit={!!leadForm?.leadNo}
        fields={LEAD_FIELDS}
        value={(leadForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setLeadForm(v as Partial<Lead>)}
        onSubmit={() => leadForm && saveLead.mutate(leadForm)}
        submitting={saveLead.isPending}
      />

      {dialog}
    </div>
  );
}

export default function LocationsPage() {
  return <Suspense fallback={null}><LocationsInner /></Suspense>;
}
