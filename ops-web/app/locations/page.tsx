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
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Site, Location, Venue, Contract, Lead, SiteAnalysis, VenueOnboarding, SiteLifecycle, PageResult } from "@/lib/types";

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
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "sites");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);
  const [siteForm, setSiteForm] = useState<Partial<Site> | null>(null);
  const [pointForm, setPointForm] = useState<Partial<Location> | null>(null);
  const [venueForm, setVenueForm] = useState<Partial<Venue> | null>(null);
  const [contractForm, setContractForm] = useState<Partial<Contract> | null>(null);
  const [leadForm, setLeadForm] = useState<Partial<Lead> | null>(null);
  const [onboardingForm, setOnboardingForm] = useState<Partial<VenueOnboarding> | null>(null);

  const canVenue = allow("location:venue:update");
  const canContract = allow("location:contract:update");
  const canLead = allow("location:lead:update");

  const q = useQuery<PageResult<Site | Location | Venue | Contract | Lead | SiteAnalysis | VenueOnboarding | SiteLifecycle>>({
    queryKey: ["place", tab, page, keyword],
    queryFn: () =>
      tab === "sites" ? api.listSites({ page, size: SIZE, keyword })
      : tab === "points" ? api.listLocations({ page, size: SIZE, keyword })
      : tab === "venues" ? api.listVenues({ page, size: SIZE, keyword })
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
    mutationFn: (l: Partial<Location>) => api.savePoint(l),
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

  const siteCols: Column<Site>[] = [
    { header: "站点号", cell: (s) => <span className="font-medium">{s.siteNo}</span> },
    { header: "名称", cell: (s) => s.name },
    { header: "场地方", cell: (s) => <span className="text-muted-foreground">{s.venueName}</span> },
    { header: "区域", cell: (s) => s.regionId },
    { header: "归属", cell: (s) => s.agentNo ? <Badge tone="outline">代理 {s.agentNo}</Badge> : <Badge tone="muted">平台直营</Badge> },
    { header: "点位/设备", cell: (s) => <span className="tabular-nums">{s.pointCount} / {s.cabinetCount}</span> },
    { header: "状态", cell: (s) => s.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">暂停</Badge> },
    { header: "操作", cell: (s) => allow("location:poi:update") ? <Button size="sm" variant="outline" onClick={() => setSiteForm(s)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const pointCols: Column<Location>[] = [
    { header: "点位号", cell: (l) => <span className="font-medium">{l.locationNo}</span> },
    { header: "名称", cell: (l) => l.name },
    { header: "所属站点", cell: (l) => <span className="text-muted-foreground">{l.siteName}</span> },
    { header: "位置", cell: (l) => l.spotDesc },
    { header: "设备数", cell: (l) => <span className="tabular-nums">{l.cabinetCount}</span> },
    { header: "状态", cell: (l) => l.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">暂停</Badge> },
    { header: "操作", cell: (l) => allow("location:poi:update") ? <Button size="sm" variant="outline" onClick={() => setPointForm(l)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const venueCols: Column<Venue>[] = [
    { header: "编号", cell: (v) => <span className="font-medium">{v.venueNo}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "联系方式", cell: (v) => <span className="text-muted-foreground">{v.contact}</span> },
    { header: "行业", cell: (v) => v.industry },
    { header: "站点数", cell: (v) => <span className="tabular-nums">{v.locationCount}</span> },
    { header: "操作", cell: (v) => canVenue ? <Button size="sm" variant="outline" onClick={() => setVenueForm(v)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
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

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      {tab === "sites" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称"
          onAdd={allow("location:poi:create") ? () => setSiteForm({ status: "ACTIVE", sceneType: "商场", agentNo: null }) : undefined} addLabel="新增站点" />
      )}
      {tab === "points" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索点位号 / 名称"
          onAdd={allow("location:poi:create") ? () => setPointForm({ status: "ACTIVE" }) : undefined} addLabel="新增点位" />
      )}
      {tab === "venues" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地方名称"
          onAdd={canVenue ? () => setVenueForm({ locationCount: 0 }) : undefined} addLabel="新增场地方" />
      )}
      {tab === "contracts" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地方 / 站点"
          onAdd={canContract ? () => setContractForm({ status: "ACTIVE", shareRate: 0.15, entryFee: 0 }) : undefined} addLabel="新增合同" />
      )}
      {tab === "crm" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索线索号 / 场地 / 负责人"
          onAdd={canLead ? () => setLeadForm({ stage: "NEW", expectSites: 1 }) : undefined} addLabel="新增线索" />
      )}
      {tab === "analysis" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称" />
      )}
      {tab === "onboarding" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地名称 / 联系人"
          onAdd={canVenue ? () => setOnboardingForm({ status: "PENDING", industry: "购物中心" }) : undefined} addLabel="新增申请" />
      )}
      {tab === "lifecycle" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称 / 负责人" />
      )}
      {tab === "sites" && <DataTable rowKey={(s: Site) => s.siteNo} columns={siteCols} rows={q.data?.list as Site[]} loading={q.isLoading} />}
      {tab === "points" && <DataTable rowKey={(l: Location) => l.locationNo} columns={pointCols} rows={q.data?.list as Location[]} loading={q.isLoading} />}
      {tab === "venues" && <DataTable rowKey={(v: Venue) => v.venueNo} columns={venueCols} rows={q.data?.list as Venue[]} loading={q.isLoading} />}
      {tab === "contracts" && <DataTable rowKey={(c: Contract) => c.contractNo} columns={ctCols} rows={q.data?.list as Contract[]} loading={q.isLoading} />}
      {tab === "crm" && <DataTable rowKey={(l: Lead) => l.leadNo} columns={leadCols} rows={q.data?.list as Lead[]} loading={q.isLoading} />}
      {tab === "analysis" && <DataTable rowKey={(a: SiteAnalysis) => a.siteNo} columns={analysisCols} rows={q.data?.list as SiteAnalysis[]} loading={q.isLoading} />}
      {tab === "onboarding" && <DataTable rowKey={(o: VenueOnboarding) => o.onboardingNo} columns={onboardingCols} rows={q.data?.list as VenueOnboarding[]} loading={q.isLoading} />}
      {tab === "lifecycle" && <DataTable rowKey={(l: SiteLifecycle) => l.siteNo} columns={lifecycleCols} rows={q.data?.list as SiteLifecycle[]} loading={q.isLoading} />}
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
          <Field label="区域"><Input value={siteForm.regionId ?? ""} onChange={(e) => setSiteForm({ ...siteForm, regionId: e.target.value })} placeholder="Dubai North" /></Field>
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
            <Select className="w-full" value={pointForm.status ?? "ACTIVE"} onChange={(e) => setPointForm({ ...pointForm, status: e.target.value as Location["status"] })}>
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
    </div>
  );
}

export default function LocationsPage() {
  return <Suspense fallback={null}><LocationsInner /></Suspense>;
}
