"use client";

import { Suspense, useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE, RECENT_LIMIT } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Input, Select } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { StatusBadge, statusOptions, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
// 坪效周期复用报表域的枚举与缺省值：同一套 REPORT_PERIODS，避免「近 30 日」两处含义不同
import { REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod } from "@/lib/types";
import { Timeline } from "@/components/ui/timeline";
import { DateInput } from "@/components/ui/date-input";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { money, fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { useAuth } from "@/lib/auth";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { nextSiteStages, SITE_COORD_BOUNDS, LEAD_FOLLOW_CHANNELS, ATTACH_EXTS, ATTACH_MAX_SIZE } from "@/lib/types";
import type { Site, SitePoint, Venue, Contract, Lead, LeadStage, LeadFollowChannel, SiteAnalysis, VenueOnboarding, SiteLifecycle, SiteStage, PageResult, Region } from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 「站点/点位/合同」在菜单里叫「站点管理 / 点位管理 / 进场合同」——以菜单为准。
const TAB_KEYS = ["sites", "points", "venues", "contracts", "onboarding", "crm", "analysis", "lifecycle"] as const;
const LEAD_STAGE: StatusMap<LeadStage> = {
  NEW: { label: "新线索", tone: "muted" },
  CONTACTED: { label: "已接触", tone: "outline" },
  NEGOTIATING: { label: "洽谈中", tone: "warning" },
  SIGNED: { label: "已签约", tone: "success" },
  LOST: { label: "已流失", tone: "danger" },
};
/** 入驻审核状态。原为组件内的就地 Record + 内联徽标（色调现取现用），收敛成 StatusMap 走 StatusBadge。 */
const OB_STATUS: StatusMap<VenueOnboarding["status"]> = {
  PENDING: { label: "待审核", tone: "warning" },
  APPROVED: { label: "已通过", tone: "success" },
  REJECTED: { label: "已驳回", tone: "danger" },
};
/**
 * 门店生命周期阶段。键序 = 正常推进顺序（潜在 → 签约 → 上线 → 运营 → 流失/关闭），
 * **不是状态机**：可进可退由 `nextSiteStages` 说了算，这里只管「叫什么、什么色」。
 */
const LC_STAGE: StatusMap<SiteStage> = {
  PROSPECTING: { label: "潜在", tone: "muted" },
  SIGNED: { label: "已签约", tone: "outline" },
  LIVE: { label: "上线", tone: "warning" },
  ACTIVE: { label: "运营中", tone: "success" },
  CHURNED: { label: "流失", tone: "danger" },
  CLOSED: { label: "关闭", tone: "muted" },
};
const SCENES = ["商场", "机场", "餐饮", "地铁", "写字楼"];
// 下拉选项由徽标映射表派生：原先是手抄的第二份，改文案会漏一处
const LEAD_STAGE_OPTIONS = statusOptions(LEAD_STAGE);
const FOLLOW_CHANNEL_LABEL: Record<LeadFollowChannel, string> = {
  CALL: "电话", VISIT: "拜访", WHATSAPP: "WhatsApp", EMAIL: "邮件", OTHER: "其他",
};
/** 坐标展示：固定 6 位小数（与 DDL 的 DECIMAL(10,6) 同精度）；非数显示为「未填」而不是 NaN。 */
const fmtCoord = (lat: number, lng: number) =>
  Number.isFinite(lat) && Number.isFinite(lng) ? `${lat.toFixed(6)}, ${lng.toFixed(6)}` : "未填";
/** 附件大小：只到 MB/KB，够判断「是不是整本没压缩的 PDF」。 */
const fmtSize = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);
// file input 的 accept 与提示文案都由 SSOT 派生，改格式白名单只改 lib/types/location.ts 一处
const ATTACH_ACCEPT = ATTACH_EXTS.map((e) => `.${e}`).join(",");
const ATTACH_ACCEPT_LABEL = ATTACH_EXTS.join(" / ").toUpperCase();
const ATTACH_MAX_MB = ATTACH_MAX_SIZE / 1024 / 1024;
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
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setShowArchived(false); };
  const tabs = useNavTabs("/locations", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const [keyword, setKeyword] = useState("");
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位，避免在合同页残留一个看不见的过滤态。
  const [showArchived, setShowArchived] = useState(false);
  // 站点坪效周期。复用报表域的 REPORT_PERIODS/缺省值 —— 自己拼一套 label，
  // 「近 30 日」在坪效页和点位报表页就会是两个窗口。
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  const [siteForm, setSiteForm] = useState<Partial<Site> | null>(null);
  const [pointForm, setPointForm] = useState<Partial<SitePoint> | null>(null);
  const [venueForm, setVenueForm] = useState<Partial<Venue> | null>(null);
  const [contractForm, setContractForm] = useState<Partial<Contract> | null>(null);
  const [leadForm, setLeadForm] = useState<Partial<Lead> | null>(null);
  const [onboardingForm, setOnboardingForm] = useState<Partial<VenueOnboarding> | null>(null);
  // 阶段流转抽屉：目标阶段与备注独立于行数据，开抽屉时按「第一个合法目标」初始化
  const [stageRow, setStageRow] = useState<SiteLifecycle | null>(null);
  const [stageTo, setStageTo] = useState<SiteStage>("SIGNED");
  const [stageReason, setStageReason] = useState("");
  // 线索详情抽屉：与「编辑」分开——编辑改的是档案字段，详情看的是跟进流水，混在一个抽屉里会让
  // 「改了字段但没记跟进」变成常态（阶段悄悄变了没人知道为什么，正是本次要补的窟窿）
  const [leadDetail, setLeadDetail] = useState<Lead | null>(null);
  const [fuContent, setFuContent] = useState("");
  const [fuChannel, setFuChannel] = useState<LeadFollowChannel>("CALL");
  const [fuStage, setFuStage] = useState<LeadStage | "">(""); // 空=只记跟进不动阶段
  const [fuNextAt, setFuNextAt] = useState("");
  // 合同附件抽屉（假上传，拍板点 #3）
  const [attachRow, setAttachRow] = useState<Contract | null>(null);

  const username = useAuth((s) => s.username);
  const canVenue = allow("location:venue:update");
  const canContract = allow("location:contract:update");
  const canLead = allow("location:lead:update");

  // 站点表单的区域下拉数据源（system 域字典）
  const regionsQ = useQuery({ queryKey: ["regions-dict"], queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }) });
  const q = useQuery<PageResult<Site | SitePoint | Venue | Contract | Lead | SiteAnalysis | VenueOnboarding | SiteLifecycle>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["place", tab, paging.page, paging.size, keyword, showArchived, period],
    queryFn: () =>
      tab === "sites" ? api.listSites({ page: paging.page, size: paging.size, keyword, showArchived })
      : tab === "points" ? api.listLocations({ page: paging.page, size: paging.size, keyword, showArchived })
      : tab === "venues" ? api.listVenues({ page: paging.page, size: paging.size, keyword, showArchived })
      : tab === "crm" ? api.listLeads({ page: paging.page, size: paging.size, keyword })
      : tab === "analysis" ? api.listSiteAnalysis({ page: paging.page, size: paging.size, keyword, period })
      : tab === "onboarding" ? api.listVenueOnboardings({ page: paging.page, size: paging.size, keyword })
      : tab === "lifecycle" ? api.listSiteLifecycles({ page: paging.page, size: paging.size, keyword })
      : api.listContracts({ page: paging.page, size: paging.size, keyword }),
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

  // 跟进流水：只查当前详情线索的记录（同订单干预历史的做法）
  const followUpsQ = useQuery({
    queryKey: ["lead-follow-ups", leadDetail?.leadNo],
    queryFn: () => api.listLeadFollowUps(leadDetail!.leadNo, { size: RECENT_LIMIT }),
    enabled: !!leadDetail,
  });
  const addFollowUp = useMutation({
    mutationFn: (v: { leadNo: string }) =>
      api.addLeadFollowUp(v.leadNo, {
        content: fuContent, channel: fuChannel,
        stage: fuStage || undefined, // 空串不能透传：后端按「阶段非法」拒绝
        owner: username || undefined, nextAt: fuNextAt || undefined,
      }),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["lead-follow-ups"] });
      qc.invalidateQueries({ queryKey: ["place", "crm"] });
      notify.success(r.fromStage ? `已记跟进并推进到「${LEAD_STAGE[r.toStage].label}」` : "已记跟进");
      // 详情抽屉里的阶段/更新时间立刻跟上落库结果，不等列表刷新（抽屉盖着列表，看不见）
      setLeadDetail((prev) => (prev ? { ...prev, stage: r.toStage, updatedAt: r.createdAt } : prev));
      setFuContent(""); setFuStage(""); setFuNextAt("");
    },
  });

  // 合同附件：两个动作都返回整份合同，直接回填抽屉，避免再拉一次列表
  /**
   * 回填附件抽屉。**必须浅拷贝**：mock 层返回的是库里那个对象本身，直接 setState 同一引用
   * React 会判定没变而不重渲染，表现为「上传成功了但列表没动」。接真后端后是新对象，拷贝无害。
   */
  const refreshAttachRow = (c: Contract) => {
    qc.invalidateQueries({ queryKey: ["place", "contracts"] });
    setAttachRow({ ...c });
  };
  const addAttach = useMutation({
    mutationFn: (v: { contractNo: string; fileName: string; size: number }) =>
      api.addContractAttachment(v.contractNo, { fileName: v.fileName, size: v.size, uploadedBy: username || undefined }),
    onSuccess: (c, v) => { refreshAttachRow(c); notify.success(`已登记附件 ${v.fileName}`); },
  });
  const removeAttach = useMutation({
    mutationFn: (v: { contractNo: string; attachNo: string }) => api.removeContractAttachment(v.contractNo, v.attachNo),
    onSuccess: (c) => { refreshAttachRow(c); notify.success("附件已移除"); },
  });

  const changeStage = useMutation({
    mutationFn: (v: { siteNo: string; stage: SiteStage; reason: string }) =>
      // gmvLtm 不传：它是阶段决策快照，由服务端沿用上一次的值，手填只会污染快照
      api.changeSiteStage(v.siteNo, { stage: v.stage, reason: v.reason, operator: username || undefined }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["place", "lifecycle"] }); notify.success("阶段已推进"); setStageRow(null); },
  });
  /** 开线索详情：清掉上一条线索残留的跟进草稿，避免把 A 的跟进内容记到 B 头上。 */
  const openLeadDetail = (l: Lead) => {
    setFuContent(""); setFuChannel("CALL"); setFuStage(""); setFuNextAt("");
    setLeadDetail(l);
  };

  /** 开流转抽屉：目标阶段预置成第一个合法值，避免默认值恰好等于当前阶段（后端会拒绝空转）。 */
  const openStage = (l: SiteLifecycle) => {
    setStageTo(nextSiteStages(l.stage)[0]);
    setStageReason("");
    setStageRow(l);
  };

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

  // 业务号列一律 txt-strong（§12.3 主键列加强）；计数/金额列 text-right + tabular-nums（§12.4）
  const siteCols: Column<Site>[] = [
    { header: "站点号", cell: (s) => <span className="txt-strong tabular-nums">{s.siteNo}</span> },
    { header: "名称", cell: (s) => s.name },
    { header: "场地方", cell: (s) => <span className="text-muted-foreground">{s.venueName}</span> },
    { header: "区域", cell: (s) => <span title={s.regionId}>{s.regionName}</span> },
    { header: "归属", cell: (s) => s.agentNo ? <Badge tone="outline">代理 {s.agentNo}</Badge> : <Badge tone="muted">平台直营</Badge> },
    { header: "点位/设备", className: "text-right", cell: (s) => <span className="tabular-nums">{s.pointCount} / {s.cabinetCount}</span> },
    // 坐标上列表：地图少了一个点时，能当场看出是哪个站点没填/填错，不用点进抽屉逐个翻
    { header: "坐标", cell: (s) => <span className="tabular-nums text-muted-foreground">{fmtCoord(s.lat, s.lng)}</span> },
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
  const venueCols: Column<Venue>[] = [
    { header: "编号", cell: (v) => <span className="txt-strong tabular-nums">{v.venueNo}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "联系方式", cell: (v) => <span className="text-muted-foreground">{v.contact}</span> },
    { header: "行业", cell: (v) => v.industry },
    { header: "站点数", className: "text-right", cell: (v) => <span className="tabular-nums">{v.locationCount}</span> },
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
    { header: "合同号", cell: (c) => <span className="txt-strong tabular-nums">{c.contractNo}</span> },
    { header: "场地方", cell: (c) => c.venueName },
    { header: "站点", cell: (c) => <span className="text-muted-foreground">{c.siteName}</span> },
    { header: "分成", className: "text-right", cell: (c) => <span className="tabular-nums">{(c.shareRate * 100).toFixed(0)}%</span> },
    // 进场费不走 money()：Contract 上没有 currency 字段（见 lib/types/location.ts），
    // 硬编一个币种反而会骗人；先按数字列对齐，币种待契约补上再接
    { header: "进场费", className: "text-right", cell: (c) => <span className="tabular-nums">{c.entryFee}</span> },
    { header: "到期", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.endAt)}</span> },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">有效</Badge> : <Badge tone="danger">过期</Badge> },
    // 附件数上列表：「哪些合同还没扫描件」是进场合同最常被问的一件事，藏在抽屉里就没人查
    {
      header: "扫描件",
      cell: (c) => c.attachments.length
        ? <Badge tone="outline">{c.attachments.length} 份</Badge>
        : <Badge tone="warning">缺</Badge>,
    },
    {
      header: "操作",
      cell: (c) => (
        <div className="flex gap-2">
          {/* 附件入口对只读角色也开：看合同有没有扫描件不需要写权限，能不能上传由屉内判定 */}
          <Button size="sm" variant="outline" onClick={() => setAttachRow(c)}>附件</Button>
          {canContract && <Button size="sm" variant="outline" onClick={() => setContractForm(c)}>编辑</Button>}
        </div>
      ),
    },
  ];
  const leadCols: Column<Lead>[] = [
    { header: "线索号", cell: (l) => <span className="txt-strong tabular-nums">{l.leadNo}</span> },
    { header: "场地名称", cell: (l) => l.venueName },
    { header: "联系人", cell: (l) => <span className="text-muted-foreground">{l.contact}</span> },
    { header: "阶段", cell: (l) => <StatusBadge map={LEAD_STAGE} value={l.stage} /> },
    { header: "负责人", cell: (l) => l.owner },
    { header: "预计站点数", className: "text-right", cell: (l) => <span className="tabular-nums">{l.expectSites}</span> },
    // 更新时间就是最后一次跟进时间（db 层保证两者同源），所以这一列点进详情能一眼对上时间线首条
    { header: "最后跟进", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.updatedAt)}</span> },
    {
      header: "操作",
      cell: (l) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => openLeadDetail(l)}>详情</Button>
          {canLead && <Button size="sm" variant="outline" onClick={() => setLeadForm(l)}>编辑</Button>}
        </div>
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
  const onboardingCols: Column<VenueOnboarding>[] = [
    { header: "申请号", cell: (o) => <span className="txt-strong tabular-nums">{o.onboardingNo}</span> },
    { header: "场地名称", cell: (o) => o.venueName },
    { header: "联系人", cell: (o) => <span className="text-muted-foreground">{o.contact}</span> },
    { header: "行业", cell: (o) => o.industry },
    { header: "申请时间", cell: (o) => <span className="text-muted-foreground">{fmtTime(o.requestedAt)}</span> },
    { header: "审核状态", cell: (o) => <StatusBadge map={OB_STATUS} value={o.status} /> },
    { header: "备注", cell: (o) => <span className="text-muted-foreground">{o.reviewNote ?? "-"}</span> },
    { header: "操作", cell: (o) => canVenue ? <Button size="sm" variant="outline" onClick={() => setOnboardingForm(o)}>审核</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const lifecycleCols: Column<SiteLifecycle>[] = [
    { header: "站点号", cell: (l) => <span className="txt-strong tabular-nums">{l.siteNo}</span> },
    { header: "站点名称", cell: (l) => l.siteName },
    { header: "阶段", cell: (l) => <StatusBadge map={LC_STAGE} value={l.stage} /> },
    { header: "阶段更新", cell: (l) => <span className="text-muted-foreground tabular-nums">{l.stageAt}</span> },
    { header: "负责人", cell: (l) => l.owner },
    { header: "GMV (LTM)", className: "text-right", cell: (l) => <span className="tabular-nums">{money(l.gmvLtm, l.currency)}</span> },
    {
      header: "操作",
      // 无合法目标阶段（终态）就不给按钮——可用性判定只认 nextSiteStages，页面不另写一套阶段规则
      cell: (l) => canVenue && nextSiteStages(l.stage).length > 0
        ? <Button size="sm" variant="outline" onClick={() => openStage(l)}>推进阶段</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const onSearch = (v: string) => { setKeyword(v); paging.reset(); };

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
        { header: "坐标", value: (s) => fmtCoord(s.lat, s.lng) },
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
        { header: "扫描件", value: (c) => (c.attachments.length ? `${c.attachments.length} 份` : "缺") },
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
      exportCsv<SiteAnalysis>(`站点坪效-${periodLabel(period)}`, [
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
  const archivedToggle = <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
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
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称" onExport={onExport}>
          <FilterSelect
            value={period}
            onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }}
            options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
            aria-label="按统计周期筛选"
          />
        </Toolbar>
      )}
      {tab === "onboarding" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地名称 / 联系人" onExport={onExport}
          onAdd={canVenue ? () => setOnboardingForm({ status: "PENDING", industry: "购物中心" }) : undefined} addLabel="新增申请" />
      )}
      {tab === "lifecycle" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索站点号 / 名称 / 负责人" onExport={onExport} />
      )}
      {tab === "sites" && <DataTable rowKey={(s: Site) => s.siteNo} columns={siteCols} rows={q.data?.list as Site[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的站点——换个关键词，或先「新增站点」建档" : "没有在用的站点——可能都已归档（打开「显示已归档」查看），或先「新增站点」建档"} />}
      {tab === "points" && <DataTable rowKey={(l: SitePoint) => l.locationNo} columns={pointCols} rows={q.data?.list as SitePoint[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的点位——换个关键词，或先「新增点位」" : "没有在用的点位——点位隶属站点，先建站点再在此新增，或打开「显示已归档」查看已归档点位"} />}
      {tab === "venues" && <DataTable rowKey={(v: Venue) => v.venueNo} columns={venueCols} rows={q.data?.list as Venue[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的场地方——换个关键词，或先「新增场地方」" : "没有在用的场地方——可能都已归档（打开「显示已归档」查看），或先「新增场地方」建档"} />}
      {tab === "contracts" && <DataTable rowKey={(c: Contract) => c.contractNo} columns={ctCols} rows={q.data?.list as Contract[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无合同——合同绑定「场地方 × 站点」，请先建好两者再「新增合同」" />}
      {tab === "crm" && <DataTable rowKey={(l: Lead) => l.leadNo} columns={leadCols} rows={q.data?.list as Lead[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无线索——BD 拓展的场地线索会出现在这里，可点「新增线索」手工录入" />}
      {tab === "analysis" && <DataTable rowKey={(a: SiteAnalysis) => a.siteNo} columns={analysisCols} rows={q.data?.list as SiteAnalysis[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={`${periodLabel(period)}内没有坪效数据——统计截至昨日（T+1），新站点需先产生订单；可换更长的周期再看`} />}
      {tab === "onboarding" && <DataTable rowKey={(o: VenueOnboarding) => o.onboardingNo} columns={onboardingCols} rows={q.data?.list as VenueOnboarding[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无入驻申请——门店自助提交的申请会进入此列表待审核，也可点「新增申请」代录" />}
      {tab === "lifecycle" && <DataTable rowKey={(l: SiteLifecycle) => l.siteNo} columns={lifecycleCols} rows={q.data?.list as SiteLifecycle[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无生命周期记录——站点签约后自动进入跟踪，尚无签约站点时此处为空" />}
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

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
          {/*
            坐标必填：地图撒点直读这两个字段，留空的站点在地图上是「凭空消失」而不是报错。
            用两个数字框而不是地图选点：静态导出下地图底图依赖 NEXT_PUBLIC_GMAPS_KEY，缺 key 时
            地图整体降级为列表占位（见 components/ui/site-map.tsx）——若把编辑入口挂在地图上，
            没配 key 的环境就彻底没法维护坐标了。数字框与 key 无关，任何环境都能改。
          */}
          <Field label="经纬度（地图撒点）">
            <div className="flex gap-2">
              <Input type="number" step="0.000001" className="w-full" placeholder={`纬度 lat ${SITE_COORD_BOUNDS.latMin}~${SITE_COORD_BOUNDS.latMax}`}
                value={siteForm.lat ?? ""} onChange={(e) => setSiteForm({ ...siteForm, lat: e.target.value === "" ? undefined : Number(e.target.value) })} />
              <Input type="number" step="0.000001" className="w-full" placeholder={`经度 lng ${SITE_COORD_BOUNDS.lngMin}~${SITE_COORD_BOUNDS.lngMax}`}
                value={siteForm.lng ?? ""} onChange={(e) => setSiteForm({ ...siteForm, lng: e.target.value === "" ? undefined : Number(e.target.value) })} />
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              取值范围 纬度 {SITE_COORD_BOUNDS.latMin}~{SITE_COORD_BOUNDS.latMax} · 经度 {SITE_COORD_BOUNDS.lngMin}~{SITE_COORD_BOUNDS.lngMax}（当前运营国家 UAE）。
              经纬度写反在全球范围内也合法，但会把点扔到海里，保存时会被拒绝。地图底图缺 key 时降级为占位列表，坐标照样生效。
            </div>
          </Field>
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

      {/* 门店生命周期 阶段流转：目标阶段由 SSOT 过滤（排掉当前阶段），操作人与原因随流转留痕 */}
      <Drawer
        open={!!stageRow}
        onOpenChange={(o) => !o && setStageRow(null)}
        title={`推进阶段 ${stageRow?.siteNo ?? ""}`}
        desc="阶段是场地经营的对外口径：每次流转都会留痕（谁、何时、从哪到哪、为什么），不可撤回"
        width="w-[520px]"
        footer={stageRow && canVenue && (
          <>
            <Button variant="outline" onClick={() => setStageRow(null)}>取消</Button>
            <Button disabled={changeStage.isPending}
              onClick={() => changeStage.mutate({ siteNo: stageRow.siteNo, stage: stageTo, reason: stageReason })}
            >确认推进</Button>
          </>
        )}
      >
        {stageRow && (<>
          <Field label="站点">{stageRow.siteName}（{stageRow.siteNo}）</Field>
          <Field label="当前阶段">
            <StatusBadge map={LC_STAGE} value={stageRow.stage} />
            <span className="ml-2 text-muted-foreground">自 {stageRow.stageAt}</span>
          </Field>
          <Field label="目标阶段">
            <Select className="w-full" value={stageTo} onChange={(e) => setStageTo(e.target.value as SiteStage)}>
              {nextSiteStages(stageRow.stage).map((s) => <option key={s} value={s}>{LC_STAGE[s].label}</option>)}
            </Select>
            {/* 说明「为什么能往回走」：生命周期没有单向状态机，流失/关闭的店重签回来是正常业务 */}
            <div className="mt-1 text-xs text-muted-foreground">
              阶段可进可退（流失/关闭的店重新签回来属正常业务），约束靠留痕而非锁死路径
            </div>
          </Field>
          <Field label="操作人">{username || "admin"}</Field>
          <Field label="流转原因">
            <Input className="w-full" value={stageReason} placeholder="如：合同已签回，2026-08-01 进场施工"
              onChange={(e) => setStageReason(e.target.value)} />
          </Field>
          <Field label="GMV (LTM)">
            <span className="tabular-nums">{money(stageRow.gmvLtm, stageRow.currency)}</span>
            {/* 提前挡住「这里为什么不能改 GMV」的疑问：它是快照，实时值在坪效页 */}
            <span className="ml-2 text-muted-foreground">阶段决策快照，沿用上次值；实时口径见「站点坪效」</span>
          </Field>
        </>)}
      </Drawer>

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

      {/*
        线索详情 + 跟进时间线。时间线与「记一条跟进」放同一个抽屉：BD 现实里是「看完历史顺手记一条」，
        拆成两个抽屉会多一次开合，而记跟进恰恰是最需要低摩擦的动作（摩擦一大就没人记，阶段就变成黑箱）。
      */}
      <Drawer
        open={!!leadDetail}
        onOpenChange={(o) => !o && setLeadDetail(null)}
        title={leadDetail ? `线索 ${leadDetail.leadNo} · ${leadDetail.venueName}` : ""}
        desc="跟进记录是 append-only 流水：只能新增，不能改也不能删，阶段变化必须由某一条跟进来解释"
        width="w-[560px]"
      >
        {leadDetail && (<>
          <Field label="场地 / 联系人">{leadDetail.venueName} · {leadDetail.contact}</Field>
          <Field label="当前阶段">
            <StatusBadge map={LEAD_STAGE} value={leadDetail.stage} />
            <span className="ml-2 text-muted-foreground">负责人 {leadDetail.owner} · 预计 {leadDetail.expectSites} 站</span>
          </Field>
          <Field label="跟进记录">
            <Timeline
              loading={followUpsQ.isLoading}
              empty="尚无跟进记录——这条线索还没人接触过，可在下方记第一条"
              items={(followUpsQ.data?.list ?? []).map((x) => ({
                key: x.followNo,
                badge: { label: FOLLOW_CHANNEL_LABEL[x.channel], tone: "outline" as const },
                meta: `${fmtTime(x.createdAt)} · ${x.owner}${x.nextAt ? ` · 下次 ${x.nextAt}` : ""}`,
                // 阶段没动的跟进只显示当时阶段，不造「A → A」的假迁移
                change: x.fromStage
                  ? `${LEAD_STAGE[x.fromStage].label} → ${LEAD_STAGE[x.toStage].label}`
                  : `阶段 ${LEAD_STAGE[x.toStage].label}`,
                text: x.content,
              }))}
            />
          </Field>

          {canLead ? (
            <div className="mt-4 border-t border-[var(--border)] pt-4">
              <div className="mb-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground/70">记一条跟进</div>
              <Field label="跟进方式">
                <Select className="w-full" value={fuChannel} onChange={(e) => setFuChannel(e.target.value as LeadFollowChannel)}>
                  {LEAD_FOLLOW_CHANNELS.map((c) => <option key={c} value={c}>{FOLLOW_CHANNEL_LABEL[c]}</option>)}
                </Select>
              </Field>
              <Field label="跟进内容">
                <Input className="w-full" value={fuContent} placeholder="如：现场看点位，谈分成比例，对方要求月结"
                  onChange={(e) => setFuContent(e.target.value)} />
              </Field>
              {/* 阶段与跟进同一次提交：先记录再改阶段会漏，改了阶段没记录更糟 */}
              <Field label="顺带推进阶段（可不选）">
                <Select className="w-full" value={fuStage} onChange={(e) => setFuStage(e.target.value as LeadStage | "")}>
                  <option value="">不改阶段，仅记跟进</option>
                  {LEAD_STAGE_OPTIONS.filter((o) => o.value !== leadDetail.stage).map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </Select>
              </Field>
              <Field label="下次跟进（可不填）">
                <DateInput value={fuNextAt} onChange={(e) => setFuNextAt(e.target.value)} />
              </Field>
              <Button
                size="sm"
                disabled={addFollowUp.isPending || !fuContent.trim()}
                onClick={() => addFollowUp.mutate({ leadNo: leadDetail.leadNo })}
              >记录跟进</Button>
            </div>
          ) : (
            <ReadOnlyNotice className="mt-4" what="线索跟进" perm="location:lead:update" note="不能新增跟进记录，也不能推进阶段" />
          )}
        </>)}
      </Drawer>

      {/*
        合同扫描件（拍板点 #3：**做假上传**）。选文件后只取 File.name / File.size 登记一条记录，
        字节流既不读也不传 —— 所以列表里没有「下载/预览」入口：给一个点不开的链接比明确没有更难查。
        交互保留真实上传的全部环节（选文件 → 校验格式/大小 → 落库 → 列表出现 → 可移除）。
      */}
      <Drawer
        open={!!attachRow}
        onOpenChange={(o) => !o && setAttachRow(null)}
        title={attachRow ? `合同附件 ${attachRow.contractNo}` : ""}
        desc="进场合同扫描件。当前为 mock 阶段：只登记文件名与大小，文件本体不会被上传或保存"
        width="w-[560px]"
      >
        {attachRow && (<>
          <Field label="合同">{attachRow.venueName} · {attachRow.siteName}</Field>
          <Field label="已登记扫描件">
            {attachRow.attachments.length === 0 ? (
              <span className="text-muted-foreground">暂无扫描件——合同签回后请把扫描件登记在此，便于对账时追溯分成口径</span>
            ) : (
              <ul className="space-y-2">
                {attachRow.attachments.map((a) => (
                  <li key={a.attachNo} className="flex items-center gap-2 rounded-field bg-muted px-3 py-2">
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium">{a.fileName}</div>
                      <div className="text-xs text-muted-foreground tabular-nums">
                        {a.attachNo} · {fmtSize(a.size)} · {a.uploadedBy} · {fmtTime(a.uploadedAt)}
                      </div>
                    </div>
                    {canContract && (
                      <Button size="sm" variant="outline" disabled={removeAttach.isPending}
                        onClick={async () => {
                          if (await confirm({ title: "移除附件", desc: `确认移除「${a.fileName}」？附件记录会直接消失，不进归档。`, confirmText: "移除", danger: true })) {
                            removeAttach.mutate({ contractNo: attachRow.contractNo, attachNo: a.attachNo });
                          }
                        }}
                      >移除</Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Field>

          {canContract ? (<>
            <Notice>
              仅接受 {ATTACH_ACCEPT_LABEL}，单份不超过 {ATTACH_MAX_MB}MB。当前为假上传：文件内容不会离开本机，
              系统只记下文件名、大小、上传人与时间；接后端后同一入口会改为真实上传。
            </Notice>
            {/* 圆角走五档：外框是控件槽（field），里面那颗按钮同档
                ——原先外框取 --radius 别名、按钮取 Tailwind 默认阶 */}
            <Field label="选择扫描件">
              <input
                type="file"
                accept={ATTACH_ACCEPT}
                disabled={addAttach.isPending}
                className="block w-full cursor-pointer rounded-field bg-secondary p-2.5 text-sm file:me-3 file:cursor-pointer file:rounded-field file:border-0 file:bg-primary file:px-3 file:py-1.5 file:text-primary-foreground disabled:opacity-50"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  // 立刻清空 input：同一份文件被拒（超限/同名）后要能原样重选，否则 change 不再触发
                  e.target.value = "";
                  if (f) addAttach.mutate({ contractNo: attachRow.contractNo, fileName: f.name, size: f.size });
                }}
              />
            </Field>
          </>) : (
            <ReadOnlyNotice className="mt-4" what="合同维护" perm="location:contract:update" note="不能上传或移除扫描件" />
          )}
        </>)}
      </Drawer>

      {dialog}
    </div>
  );
}

/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;

export default function LocationsPage() {
  return <Suspense fallback={null}><LocationsInner /></Suspense>;
}
