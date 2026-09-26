"use client";

import { Suspense, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab, keepWithinTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FileField } from "@/components/ui/file-field";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { StateActions } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";
import { ContractDetailDrawer, CONTRACT_STATUS } from "@/components/location/contract-detail";
import { useContractActions } from "@/components/location/contract-actions";
import { LeadDetailDrawer } from "@/components/location/lead-detail";
import { OnboardingDetailDrawer, OB_STATUS } from "@/components/location/onboarding-detail";
import { FilterCard } from "@/components/location/filter-card";
import { SummaryCard } from "@/components/ui/summary-card";
import { FileLink } from "@/components/location/file-link";
import { LEAD_STAGE, SHARE_MODE_LABEL, FOLLOW_DUE, followDue } from "@/components/location/lead-meta";
import { FILE_CATEGORY_RULES, fileSize } from "@/lib/types";
import type {
  Venue, Contract, Lead, LeadStage, LeadSaveReq, VenueOnboarding, LifecycleRow, SiteStatus, PageResult, FunnelStage,
} from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 2026-09-23 第三步：本页从 /locations 拆出，承载「场地方与拓展」这条线 ——
// 场地方档案与合同（签下来的关系）+ 拓展（线索 → 进件 → 生命周期）。
// 点位管理与站点坪效留在 /locations（属运营管理）。拆页的硬原因：
// findActiveSection 按**路径前缀**定归属，一个 URL 只能属于一个 L1。
//
// 详情抽屉读 `?no=`（RefLink 的路由规则只在 components/ref-link.tsx 一处维护）：
//   /venues?tab=crm&no=LD…  商机详情 · /venues?tab=contracts&no=CT…  合同详情 · /venues?tab=onboarding&no=OB…  进件详情
const TAB_KEYS = ["venues", "contracts", "crm", "onboarding", "lifecycle"] as const;

/**
 * 漏斗档位 = **商机阶段 ∪ 站点状态**（2026-09-25 合并后的唯一一套）。键序 = 从线索到闭店的真实先后。
 */
const LC_PHASE: StatusMap<LeadStage | SiteStatus> = {
  ...LEAD_STAGE,
  SIGNED: { label: "已签约", tone: "outline" },
  PREPARING: { label: "筹备中", tone: "default" },
  ACTIVE: { label: "营业中", tone: "success" },
  PAUSED: { label: "暂停营业", tone: "warning" },
  WITHDRAWING: { label: "撤场中", tone: "warning" },
  CLOSED: { label: "已关闭", tone: "muted" },
};
const LC_KIND: StatusMap<LifecycleRow["kind"]> = {
  LEAD: { label: "商机", tone: "outline" },
  SITE: { label: "站点", tone: "default" },
};

/** 商机视图：我在跟的（默认，含全部在跟）/ 公共线索池。池是一个独立的待办面，不是一个筛选条件。 */
const LEAD_VIEW: StatusMap<"ACTIVE" | "POOL"> = {
  ACTIVE: { label: "在跟商机", tone: "default" },
  POOL: { label: "公共线索池", tone: "warning" },
};

const SHARE_MODE_OPTS = (Object.keys(SHARE_MODE_LABEL) as (keyof typeof SHARE_MODE_LABEL)[])
  .map((k) => ({ value: k, label: SHARE_MODE_LABEL[k] }));

/**
 * @form POST /api/ops/venues
 * @form POST /api/ops/venues/{venueNo}
 */
const VENUE_FIELDS: FieldDef[] = [
  { key: "venueNo", label: "编号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "name", label: "名称", required: true, maxLength: 128, placeholder: "Dubai Mall" },
  { key: "contact", label: "联系方式", placeholder: "姓名 / 电话" },
  { key: "industry", label: "行业", placeholder: "购物中心" },
  // 「站点数」是**聚合值**（名下有几个站点），不是属性，手填必然与实际脱节 —— 表单不收。
];
/**
 * 合同字段。场地方 / 站点必须**选**不能**打** —— 合同是场地方分成的唯一依据，按名字连必然连错。
 * **没有状态**（R1）：新建一律草稿，推进走提交 / 审批 / 签署等动作；只有草稿能编辑。
 */
/**
 * @form POST /api/ops/contracts
 * @form POST /api/ops/contracts/{contractNo}
 */
function contractFieldsFor(
  venues: { value: string; label: string }[],
  sites: { value: string; label: string }[],
): FieldDef[] {
  return [
    { key: "contractNo", label: "合同号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
    { key: "venueNo", label: "场地方", type: "select", required: true,
      options: [{ value: "", label: "请选择场地方" }, ...venues] },
    { key: "siteNo", label: "站点", type: "select", required: true,
      options: [{ value: "", label: "请选择站点" }, ...sites],
      help: "只列所选场地方名下的站点" },
    // 上限 1 不是形式主义：这里填 20（本意 20%）就是**二十倍分成**，而且分完才会被发现。
    { key: "shareRate", label: "分成比例（0~1，如 0.15 = 15%）", type: "number", required: true, min: 0, max: 1 },
    { key: "entryFee", label: "进场费", type: "number", min: 0 },
    { key: "startAt", label: "生效时间", type: "date", required: true },
    { key: "endAt", label: "到期时间", type: "date", required: true },
  ];
}
/** 商机档案字段。**没有阶段**（R1）：阶段只经动作改（记跟进顺带推进 / 标记丢单 / 签约转化）。 */
/**
 * @form POST /api/ops/leads
 * @form POST /api/ops/leads/{leadNo}
 */
const LEAD_FIELDS: FieldDef[] = [
  { key: "leadNo", label: "线索号", readOnlyOnEdit: true, placeholder: "新增自动生成", section: "场地" },
  { key: "venueName", label: "场地名称", required: true, maxLength: 128, placeholder: "某商场", section: "场地",
    help: "新建时按场地名或地址查重：90 天内别人在跟的场地不能重复建，会告诉你是谁在跟" },
  { key: "address", label: "地址", maxLength: 256, placeholder: "楼宇 / 街道", section: "场地" },
  { key: "contact", label: "联系人", placeholder: "姓名 / 电话", section: "场地" },
  { key: "expectSites", label: "预计站点数", type: "number", min: 0, section: "场地" },
  { key: "nextFollowAt", label: "下次跟进", type: "date", section: "场地", help: "「今天该打谁的电话」按它排" },
  { key: "shareMode", label: "分成模式", type: "select", section: "谈判条款（转化时带进合同草稿）",
    options: [{ value: "", label: "未定" }, ...SHARE_MODE_OPTS] },
  { key: "shareRate", label: "分成比例（0~1）", type: "number", min: 0, max: 1, section: "谈判条款（转化时带进合同草稿）" },
  { key: "entryFee", label: "进场费", type: "number", min: 0, section: "谈判条款（转化时带进合同草稿）" },
  { key: "guaranteeAmount", label: "保底金额", type: "number", min: 0, section: "谈判条款（转化时带进合同草稿）" },
  { key: "termMonths", label: "期限（月）", type: "number", min: 1, section: "谈判条款（转化时带进合同草稿）" },
  { key: "exclusiveFlag", label: "独家", type: "switch", section: "谈判条款（转化时带进合同草稿）" },
];
/**
 * 负责人单独拼：`loc_lead.owner` 存的是**业务号**，它是员工号还是伙伴号由 `ownerType` 说了算。
 * 归属是伙伴时，商机签下并指定落成站点后会自动写一行「拓展」责任 —— 那是拓展佣金的依据。
 */
/**
 * @form POST /api/ops/leads
 * @form POST /api/ops/leads/{leadNo}
 */
function leadFieldsFor(
  ownerType: string,
  employees: { value: string; label: string }[],
  agents: { value: string; label: string }[],
  sites: { value: string; label: string }[],
): FieldDef[] {
  const partner = ownerType === "AGENT";
  return [
    ...LEAD_FIELDS.slice(0, 6),
    {
      key: "ownerType", label: "归属方类型", type: "select", section: "归属",
      options: [{ value: "STAFF", label: "自己人（员工）" }, { value: "AGENT", label: "伙伴（代理商）" }],
      help: "伙伴谈下来的，签下后自动记一行「拓展」责任，作为拓展佣金的依据",
    },
    // 切换类型时候选集整个换掉：两个命名空间的号混填进同一列，对不上人且不报错
    partner
      ? { key: "owner", label: "归属伙伴", type: "select", section: "归属",
          options: [{ value: "", label: "请选择伙伴" }, ...agents], help: "存代理商编号" }
      : { key: "owner", label: "负责人", type: "select", section: "归属",
          options: [{ value: "", label: "我（当前登录人）" }, ...employees], help: "存员工编号，不是姓名" },
    ...(partner
      ? [{
          key: "siteNo", label: "落成站点", type: "select" as const, section: "归属",
          options: [{ value: "", label: "尚未建站" }, ...sites],
          help: "先签后建站是常态：这里留空不影响保存，站点补填上去的那一次会补写拓展责任",
        }]
      : []),
    ...LEAD_FIELDS.slice(6),
  ];
}
/** 驳回原因：必填。不给原因的话，申请人只能反复猜着重提。 */
/**
 * @form POST /api/ops/venue-onboardings/{onboardingNo}/review
 */
const REJECT_FIELDS: FieldDef[] = [
  { key: "note", label: "驳回原因", required: true, maxLength: 200,
    placeholder: "如：营业执照照片不清晰，请重新上传",
    help: "申请人会原样看到这句话" },
];
/**
 * @form POST /api/ops/venue-onboardings
 * @form POST /api/ops/venue-onboardings/{onboardingNo}
 */
const ONBOARDING_FIELDS: FieldDef[] = [
  { key: "onboardingNo", label: "申请号", readOnlyOnEdit: true, placeholder: "新增自动生成" },
  { key: "venueName", label: "场地名称", placeholder: "Al Barsha Mall" },
  { key: "contact", label: "联系人", placeholder: "姓名 + 电话" },
  { key: "industry", label: "行业", placeholder: "购物中心" },
  // 「审核状态 / 审核备注」不放进表单：审核是一个有状态机的动作，不是可以随手改的字段。
];

/** 出参 Lead（条款收在 terms 里）→ 编辑表单（条款平铺，与保存入参同形）。 */
const leadToForm = (l: Lead): LeadSaveReq => ({
  leadNo: l.leadNo, venueName: l.venueName, address: l.address, contact: l.contact,
  expectSites: l.expectSites, nextFollowAt: l.nextFollowAt?.slice(0, 10) ?? null,
  ownerType: l.ownerType, owner: l.owner, siteNo: l.siteNo,
  shareMode: l.terms?.shareMode ?? null, shareRate: l.terms?.shareRate ?? null, entryFee: l.terms?.entryFee ?? null,
  guaranteeAmount: l.terms?.guaranteeAmount ?? null, termMonths: l.terms?.termMonths ?? null,
  exclusiveFlag: l.terms?.exclusive ?? null,
});

function VenuesInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const tabs = useNavTabs("/venues", TAB_KEYS);
  const onTabChange = () => { paging.reset(); setShowArchived(false); setFilters({}); };
  const { tab, setTab } = usePageTab(tabs, onTabChange);

  // —— 深链：?no= 打开当前 tab 的详情抽屉；打开 / 关闭都回写 URL，刷新与分享不丢 ——
  const detailNo = sp.get("no");
  /** 站点开业清单「生效合同」一项的去处是 `/venues?tab=contracts&siteNo=`：只看这个站点的合同。 */
  const siteNoParam = tab === "contracts" ? sp.get("siteNo") : null;
  const openDetail = (no: string) => {
    const q = new URLSearchParams(sp.toString());
    q.set("tab", tab);
    q.set("no", no);
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
  };
  const closeDetail = () => {
    const q = new URLSearchParams(sp.toString());
    q.delete("no");
    router.replace(q.size ? `${pathname}?${q.toString()}` : pathname, { scroll: false });
  };
  const switchTab = (k: string) => {
    // 换 tab 时丢掉 no：它属于上一个 tab 的对象，带过去会在新 tab 里打开一个查无此号的抽屉
    const q = new URLSearchParams(sp.toString());
    q.delete("no");
    q.set("tab", k);
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
    setTab(k);
  };

  /*
   * 场地方的收款账户（B3）。这一页**不做账户的增删改** —— 管理界面在 /finance?tab=payout-accounts。
   * 这里只回答一个问题：**这个场地方现在能不能收到钱**。
   */
  const canReadPayout = allow("finance:payout_account:read");
  const payoutQ = useQuery({
    queryKey: ["venue-payout-accounts"],
    queryFn: () => api.listPayoutAccounts({ page: 1, size: UNPAGED_SIZE, payeeType: "VENUE" }),
    enabled: canReadPayout && tab === "venues",
  });
  const payoutByVenue = useMemo(() => {
    const m = new Map<string, string>();
    for (const a of payoutQ.data?.list ?? []) {
      if (a.status === "ACTIVE" && a.isDefault) m.set(a.payeeNo, a.accountMasked);
    }
    return m;
  }, [payoutQ.data]);

  const [keyword, setKeyword] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  /** 各 tab 的筛选（切 tab 清空）。键即后端查询参数名。 */
  const [filters, setFilters] = useState<Record<string, string>>({});
  const setFilter = (k: string, v: string) => { setFilters((f) => ({ ...f, [k]: v })); paging.reset(); };
  const toggleFilter = (k: string, v: string) => setFilter(k, filters[k] === v ? "" : v);
  const [venueForm, setVenueForm] = useState<Partial<Venue> | null>(null);
  const [contractForm, setContractForm] = useState<Partial<Contract> | null>(null);
  const [leadForm, setLeadForm] = useState<LeadSaveReq | null>(null);
  const [onboardingForm, setOnboardingForm] = useState<Partial<VenueOnboarding> | null>(null);
  const [rejectForm, setRejectForm] = useState<{ onboardingNo: string; note: string } | null>(null);
  const [attachRow, setAttachRow] = useState<Contract | null>(null);
  const [attachFiles, setAttachFiles] = useState<string[]>([]);

  const canVenue = allow("location:venue:update");
  const canCrm = allow("location:crm:update");

  // 关联字段的下拉数据源：小字典，一次拉全量不分页
  const venuesQ = useQuery({ queryKey: ["venues-dict"], queryFn: () => api.listVenues({ page: 1, size: UNPAGED_SIZE }) });
  const sitesQ = useQuery({ queryKey: ["sites-dict"], queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }) });
  const employeesQ = useQuery({ queryKey: ["employees-dict"], queryFn: () => api.listEmployees({ page: 1, size: UNPAGED_SIZE }) });
  const agentsQ = useQuery({ queryKey: ["agents-dict"], queryFn: () => api.listAgents({ page: 1, size: UNPAGED_SIZE }) });
  const venueOpts = useMemo(
    () => (venuesQ.data?.list ?? []).map((v) => ({ value: v.venueNo, label: `${v.name}（${v.venueNo}）` })),
    [venuesQ.data],
  );
  // 站点下拉按已选场地方收窄：选了 Dubai Mall 还能选到别家商场的站点，等于没约束。
  const contractSiteOpts = useMemo(() => {
    const all = sitesQ.data?.list ?? [];
    const vNo = contractForm?.venueNo;
    const scoped = vNo ? all.filter((x) => x.venueNo === vNo) : all;
    return scoped.map((x) => ({ value: x.siteNo, label: `${x.name}（${x.siteNo}）` }));
  }, [sitesQ.data, contractForm?.venueNo]);
  const employeeOpts = useMemo(
    () => (employeesQ.data?.list ?? []).filter((e) => e.status === "ACTIVE")
      .map((e) => ({ value: e.employeeNo, label: `${e.name}（${e.employeeNo}）` })),
    [employeesQ.data],
  );
  const contractFields = useMemo(() => contractFieldsFor(venueOpts, contractSiteOpts), [venueOpts, contractSiteOpts]);
  const siteOpts = useMemo(
    () => (sitesQ.data?.list ?? []).map((x) => ({ value: x.siteNo, label: `${x.name}（${x.siteNo}）` })),
    [sitesQ.data],
  );
  const agentOpts = useMemo(
    () => (agentsQ.data?.list ?? []).map((a) => ({ value: a.agentNo, label: `${a.name}（${a.agentNo}）` })),
    [agentsQ.data],
  );
  const leadFields = useMemo(
    () => leadFieldsFor(String(leadForm?.ownerType ?? "STAFF"), employeeOpts, agentOpts, siteOpts),
    [leadForm?.ownerType, employeeOpts, agentOpts, siteOpts],
  );

  const inPool = tab === "crm" && filters.view === "POOL";
  const q = useQuery<PageResult<Venue | Contract | Lead | VenueOnboarding | LifecycleRow>>({
    queryKey: ["venue-bd", tab, paging.page, paging.size, keyword, showArchived, filters, siteNoParam],
    queryFn: () => {
      const base = { page: paging.page, size: paging.size, keyword };
      switch (tab) {
        case "venues": return api.listVenues({ ...base, showArchived });
        case "crm": return api.listLeads({ ...base, stage: filters.stage || undefined, inPool });
        case "onboarding": return api.listVenueOnboardings(base);
        case "lifecycle": return api.listSiteLifecycles({ ...base, phase: filters.phase || undefined });
        default: return api.listContracts({
          ...base, siteNo: siteNoParam || undefined, status: filters.status || undefined, pendingMine: filters.pendingMine === "1" || undefined,
          endFrom: filters.endFrom || undefined, endTo: filters.endTo || undefined,
        });
      }
    },
    placeholderData: keepWithinTab(tab),
  });

  const saveVenue = useMutation({
    mutationFn: (v: Partial<Venue>) => api.saveVenue(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["venue-bd", "venues"] }); notify.success("保存成功"); setVenueForm(null); },
  });
  // 合同保存：名字由所选编号带出（不让编号与名字各说各话），并挡住「到期早于生效」。
  const submitContract = () => {
    const c = contractForm;
    if (!c) return;
    if (c.startAt && c.endAt && c.endAt < c.startAt) { notify.error("到期时间早于生效时间"); return; }
    const venueName = venuesQ.data?.list.find((v) => v.venueNo === c.venueNo)?.name ?? c.venueName ?? "";
    const siteName = sitesQ.data?.list.find((x) => x.siteNo === c.siteNo)?.name ?? c.siteName ?? "";
    saveContract.mutate({ ...c, venueName, siteName });
  };
  const saveContract = useMutation({
    mutationFn: (c: Partial<Contract>) => api.saveContract(c),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["venue-bd", "contracts"] });
      qc.invalidateQueries({ queryKey: ["contract-detail", c.contractNo] });
      notify.success(contractForm?.contractNo ? "保存成功" : `已建草稿 ${c.contractNo}，确认条款后「提交审批」`);
      setContractForm(null);
    },
  });
  /**
   * 商机保存。查重被拒时后端 409 的原话是「该场地已有商机 X 在跟进（负责人 Y）…」——
   * 全局 MutationCache 会弹出这句；抽屉不关，改完场地名 / 地址可以直接再存。
   */
  const saveLead = useMutation({
    mutationFn: (l: LeadSaveReq) => {
      const num = (v: unknown) => (v === "" || v == null ? null : Number(v));
      return api.saveLead({
        ...l,
        owner: l.owner || null, nextFollowAt: l.nextFollowAt || null, shareMode: l.shareMode || null,
        shareRate: num(l.shareRate), entryFee: num(l.entryFee), guaranteeAmount: num(l.guaranteeAmount),
        termMonths: num(l.termMonths), expectSites: num(l.expectSites) ?? 0,
      });
    },
    onSuccess: (l) => {
      qc.invalidateQueries({ queryKey: ["venue-bd", "crm"] });
      qc.invalidateQueries({ queryKey: ["lead", l.leadNo] });
      notify.success(leadForm?.leadNo ? "保存成功" : `已建商机 ${l.leadNo}`);
      setLeadForm(null);
    },
  });
  const claimLead = useMutation({
    mutationFn: (no: string) => api.claimLead(no),
    onSuccess: (l) => { qc.invalidateQueries({ queryKey: ["venue-bd", "crm"] }); qc.invalidateQueries({ queryKey: ["lead", l.leadNo] }); notify.success(`已认领 ${l.leadNo}`); },
  });
  const reviewOnboarding = useMutation({
    mutationFn: (v: { no: string; approve: boolean; note?: string }) =>
      api.reviewVenueOnboarding(v.no, v.approve, v.note),
    onSuccess: (o) => {
      qc.invalidateQueries({ queryKey: ["venue-bd", "onboarding"] });
      qc.invalidateQueries({ queryKey: ["venue-onboarding", o.onboardingNo] });
      // 通过会建出场地方，场地方列表与下拉都得跟着刷，否则下一步签合同时选不到它
      qc.invalidateQueries({ queryKey: ["venues-dict"] });
      notify.success(o.status === "APPROVED" ? `已通过，场地方 ${o.venueNo ?? ""} 已建档` : "已驳回");
    },
  });
  const saveOnboarding = useMutation({
    mutationFn: (o: Partial<VenueOnboarding>) => api.saveVenueOnboarding(o),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["venue-bd", "onboarding"] }); notify.success("保存成功"); setOnboardingForm(null); },
  });

  // 合同附件：先经文件服务上传（FileField），再按 fileNo 挂到合同上
  const addAttach = useMutation({
    mutationFn: (v: { contractNo: string; fileNos: string[] }) => api.addContractAttachment(v.contractNo, { fileNos: v.fileNos }),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["venue-bd", "contracts"] });
      qc.invalidateQueries({ queryKey: ["contract-detail", c.contractNo] });
      qc.invalidateQueries({ queryKey: ["contract-summary"] });
      setAttachRow({ ...c });   // 浅拷贝：mock 返回库里同一个对象，同引用 setState 不重渲染
      setAttachFiles([]);
      notify.success("扫描件已挂到合同上");
    },
  });
  const removeAttach = useMutation({
    mutationFn: (v: { contractNo: string; attachNo: string }) => api.removeContractAttachment(v.contractNo, v.attachNo),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["venue-bd", "contracts"] });
      qc.invalidateQueries({ queryKey: ["contract-detail", c.contractNo] });
      setAttachRow({ ...c });
      notify.success("附件已移除");
    },
  });
  const openAttach = (c: Contract) => { setAttachFiles([]); setAttachRow(c); };

  /** 摘要条。只在合同 tab 拉。 */
  const summary = useQuery({ queryKey: ["contract-summary"], queryFn: () => api.contractSummary(), enabled: tab === "contracts" });
  /** 漏斗计数。只在生命周期 tab 拉。 */
  const funnel = useQuery({ queryKey: ["site-lifecycle-funnel"], queryFn: () => api.siteLifecycleFunnel(), enabled: tab === "lifecycle" });

  const contractActions = useContractActions({ onEdit: (c) => setContractForm(c), onAttach: openAttach });

  // 归档 / 恢复（G1 软删除）。错误由全局 MutationCache 接管，页面不重复 catch。
  const invalidatePlace = () => qc.invalidateQueries({ queryKey: ["venue-bd"] });
  const archiveVenue = useMutation({ mutationFn: (no: string) => api.archiveVenue(no), onSuccess: () => { invalidatePlace(); notify.success("已归档"); } });
  const unarchiveVenue = useMutation({ mutationFn: (no: string) => api.unarchiveVenue(no), onSuccess: () => { invalidatePlace(); notify.success("已恢复"); } });

  function archivedCols<T extends { archivedAt: string | null }>(): Column<T>[] {
    return showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];
  }

  // 业务号列一律 txt-strong；计数/金额列 text-right + tabular-nums
  const venueCols: Column<Venue>[] = [
    { header: "编号", cell: (v) => <span className="txt-strong tabular-nums">{v.venueNo}</span> },
    { header: "名称", cell: (v) => v.name },
    { header: "联系方式", cell: (v) => <span className="text-muted-foreground">{v.contact}</span> },
    { header: "行业", cell: (v) => v.industry },
    { header: "站点数", className: "text-right", cell: (v) => <span className="tabular-nums">{v.locationCount}</span> },
    ...(canReadPayout ? [{
      header: "收款账户",
      cell: (v: Venue) => {
        const masked = payoutByVenue.get(v.venueNo);
        return masked
          ? <span className="font-mono txt-caption text-muted-foreground">{masked}</span>
          : (
            // 未设置就是「结算做完也打不出去」—— 标出来，别等审批被拒才发现
            <Link href="/finance?tab=payout-accounts" className="txt-caption text-warning hover:underline">
              未设置 · 去补录
            </Link>
          );
      },
    }] : []),
    ...archivedCols<Venue>(),
    {
      header: "操作",
      cell: (v) => (
        <ArchiveActions
          archived={!!v.archivedAt}
          canWrite={canVenue}
          actions={<Button size="sm" variant="outline" onClick={() => setVenueForm(v)}>编辑</Button>}
          onArchive={async () => { if (await confirm(archiveConfirm("场地方", v.venueNo, v.venueNo))) archiveVenue.mutate(v.venueNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("场地方", v.venueNo))) unarchiveVenue.mutate(v.venueNo); }}
        />
      ),
    },
  ];
  const ctCols: Column<Contract>[] = [
    {
      header: "合同号",
      cell: (c) => (
        <button className="txt-strong tabular-nums text-primary hover:underline" onClick={() => openDetail(c.contractNo)}>
          {c.contractNo}
        </button>
      ),
    },
    { header: "场地方", cell: (c) => c.venueName },
    { header: "站点", cell: (c) => <span className="text-muted-foreground">{c.siteName}</span> },
    {
      header: "分成",
      className: "text-right",
      cell: (c) => (
        <span className="tabular-nums">
          {c.terms?.shareMode === "GUARANTEE" && c.terms.guaranteeAmount != null ? `保底 ${c.terms.guaranteeAmount} + ` : ""}
          {(c.shareRate * 100).toFixed(0)}%
        </span>
      ),
    },
    {
      header: "进场费",
      className: "text-right",
      cell: (c) => <span className="tabular-nums">{c.entryFee}{c.terms?.currency ? ` ${c.terms.currency}` : ""}</span>,
    },
    { header: "到期", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.endAt)}</span> },
    {
      header: "状态",
      cell: (c) => (
        <>
          <StatusBadge map={CONTRACT_STATUS} value={c.status} />
          {c.status === "PENDING" && c.flow?.auditStage && (
            <span className="ms-1 txt-caption text-muted-foreground">
              {c.flow.auditStage === "FINANCE" ? "待财务" : "待运营"}
            </span>
          )}
          {c.flow?.termination?.status === "PENDING" && (
            <span className="ms-1 txt-caption text-warning-ink">终止待审</span>
          )}
        </>
      ),
    },
    // 附件数上列表：「哪些合同还没扫描件」是进场合同最常被问的一件事
    {
      header: "扫描件",
      cell: (c) => c.attachments.length
        ? <Badge tone="outline">{c.attachments.length} 份</Badge>
        : <Badge tone="warning">缺</Badge>,
    },
    {
      header: "操作",
      // 动作可用性与权限码一律来自 useContractActions（详情头同一份），页面不另写一套
      cell: (c) => (
        <div className="flex w-max items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => openDetail(c.contractNo)}>详情</Button>
          <StateActions actions={contractActions.actionsFor(c)} />
        </div>
      ),
    },
  ];
  const leadCols: Column<Lead>[] = [
    {
      header: "线索号",
      cell: (l) => (
        <button className="txt-strong tabular-nums text-primary hover:underline" onClick={() => openDetail(l.leadNo)}>
          {l.leadNo}
        </button>
      ),
    },
    {
      header: "场地",
      cell: (l) => (
        <div className="min-w-0">
          <div className="truncate">{l.venueName}</div>
          {l.address && <div className="truncate txt-caption text-muted-foreground">{l.address}</div>}
        </div>
      ),
    },
    { header: "联系人", cell: (l) => <span className="text-muted-foreground">{l.contact ?? "-"}</span> },
    { header: "阶段", cell: (l) => <StatusBadge map={LEAD_STAGE} value={l.stage} /> },
    // 归属方要连类型一起显示：两者的差别是「这条商机要不要付拓展佣金」
    {
      header: "归属",
      cell: (l) => l.inPool
        ? <span className="text-muted-foreground">池中{l.prevOwner ? ` · 原 ${l.prevOwner}` : ""}</span>
        : (
          <span>
            {l.owner ?? "-"}
            {l.ownerType === "AGENT" && <span className="ms-1 text-muted-foreground">伙伴</span>}
            {l.ownerType === "AGENT" && l.stage === "SIGNED" && !l.siteNo && (
              <span className="ms-1 text-muted-foreground">· 待指定站点</span>
            )}
          </span>
        ),
    },
    { header: "预计站点", className: "text-right", cell: (l) => <span className="tabular-nums">{l.expectSites}</span> },
    // 「今天该打谁的电话」靠它排：逾期与今天各给一个色，别让人自己比日期
    {
      header: "下次跟进",
      cell: (l) => {
        const due = followDue(l.nextFollowAt);
        return due === "NONE" || l.stage === "SIGNED" || l.stage === "LOST"
          ? <span className="text-muted-foreground">{l.stage === "SIGNED" || l.stage === "LOST" ? "—" : "未约"}</span>
          : <span className="inline-flex items-center gap-1.5">
              <span className="tabular-nums">{l.nextFollowAt!.slice(0, 10)}</span>
              {due !== "LATER" && <StatusBadge map={FOLLOW_DUE} value={due} />}
            </span>;
      },
    },
    // 最后跟进读 lastFollowAt（服务端维护，提醒与回收都按它算），不读 updatedAt —— 改个联系人也会动后者
    { header: "最后跟进", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.lastFollowAt ?? l.updatedAt)}</span> },
    { header: "合同", cell: (l) => <RefLink kind="contract" no={l.contractNo} /> },
    {
      header: "操作",
      cell: (l) => (
        <div className="flex w-max gap-2">
          <Button size="sm" variant="outline" onClick={() => openDetail(l.leadNo)}>详情</Button>
          {l.inPool && canCrm && (
            <Button size="sm" disabled={claimLead.isPending} onClick={async () => {
              if (await confirm({ title: `认领 ${l.venueName}？`, desc: "认领后由你负责并重新计时；超期不跟进会再次回收进线索池。" })) claimLead.mutate(l.leadNo);
            }}>认领</Button>
          )}
        </div>
      ),
    },
  ];
  const onboardingActions = (o: VenueOnboarding) => {
    if (!canVenue || o.status !== "PENDING") return null;   // 审过的进件不再给任何写入口
    return (
      <div className="flex w-max gap-2">
        <Button size="sm" variant="outline" onClick={() => setOnboardingForm(o)}>编辑</Button>
        <Button size="sm" variant="outline" onClick={async () => {
          if (await confirm({
            title: "通过这份进件？",
            desc: `通过后会按「${o.venueName}」建出场地方档案，并把场地方号回填到本申请上。`,
          })) reviewOnboarding.mutate({ no: o.onboardingNo, approve: true });
        }}>通过</Button>
        <Button size="sm" variant="outline"
          onClick={() => setRejectForm({ onboardingNo: o.onboardingNo, note: "" })}>驳回</Button>
      </div>
    );
  };
  const onboardingCols: Column<VenueOnboarding>[] = [
    {
      header: "申请号",
      cell: (o) => (
        <button className="txt-strong tabular-nums text-primary hover:underline" onClick={() => openDetail(o.onboardingNo)}>
          {o.onboardingNo}
        </button>
      ),
    },
    { header: "场地名称", cell: (o) => o.venueName },
    { header: "联系人", cell: (o) => <span className="text-muted-foreground">{o.contact}</span> },
    { header: "行业", cell: (o) => o.industry },
    { header: "申请时间", cell: (o) => <span className="text-muted-foreground">{fmtTime(o.requestedAt)}</span> },
    { header: "审核状态", cell: (o) => <StatusBadge map={OB_STATUS} value={o.status} /> },
    {
      header: "场地方",
      cell: (o) => o.venueNo
        ? <span className="tabular-nums">{o.venueNo}</span>
        : <span className="text-muted-foreground">—</span>,
    },
    { header: "备注", cell: (o) => <span className="text-muted-foreground">{o.reviewNote ?? "-"}</span> },
    {
      header: "操作",
      cell: (o) => onboardingActions(o) ?? <span className="text-muted-foreground">{o.status === "PENDING" ? "-" : "已审"}</span>,
    },
  ];
  /**
   * 漏斗明细**只读**：没有「操作」列。推商机走 CRM 的动作，推站点走「站点管理」的状态动作。
   */
  const lifecycleCols: Column<LifecycleRow>[] = [
    { header: "类型", cell: (l) => <StatusBadge map={LC_KIND} value={l.kind} /> },
    { header: "编号", cell: (l) => <RefLink kind={l.kind === "LEAD" ? "lead" : "site"} no={l.no} className="txt-strong" /> },
    { header: "名称", cell: (l) => l.name },
    { header: "当前阶段", cell: (l) => <StatusBadge map={LC_PHASE} value={l.phase} /> },
    { header: "进入时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.phaseSince)}</span> },
    {
      header: "停留",
      className: "text-right",
      // 停留天数是这张表唯一的「该催了」信号：久居一档说明卡住了
      cell: (l) => l.daysInPhase == null
        ? <span className="text-muted-foreground">-</span>
        : <span className="tabular-nums">{l.daysInPhase} 天</span>,
    },
    { header: "负责人", cell: (l) => l.owner || <span className="text-muted-foreground">-</span> },
  ];

  const onSearch = (v: string) => { setKeyword(v); paging.reset(); };

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
  function pageRows<T>(): T[] { return (q.data?.list ?? []) as T[]; }
  function archivedCsv<T extends { archivedAt: string | null }>(): CsvColumn<T>[] {
    return showArchived ? [{ header: "归档时间", value: (r) => (r.archivedAt ? fmtTime(r.archivedAt) : "") }] : [];
  }
  function exportCurrent() {
    if (tab === "venues") {
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
        { header: "状态", value: (c) => CONTRACT_STATUS[c.status]?.label ?? c.status },
        { header: "扫描件", value: (c) => (c.attachments.length ? `${c.attachments.length} 份` : "缺") },
      ], pageRows<Contract>());
    } else if (tab === "crm") {
      exportCsv<Lead>("BD 拓展 CRM", [
        { header: "线索号", value: (l) => l.leadNo },
        { header: "场地名称", value: (l) => l.venueName },
        { header: "地址", value: (l) => l.address ?? "" },
        { header: "联系人", value: (l) => l.contact ?? "" },
        { header: "阶段", value: (l) => LEAD_STAGE[l.stage]?.label ?? l.stage },
        { header: "归属", value: (l) => (l.inPool ? "公共线索池" : l.owner ?? "") },
        { header: "预计站点数", value: (l) => l.expectSites },
        { header: "下次跟进", value: (l) => l.nextFollowAt?.slice(0, 10) ?? "" },
        { header: "最后跟进", value: (l) => fmtTime(l.lastFollowAt ?? l.updatedAt) },
        { header: "合同", value: (l) => l.contractNo ?? "" },
      ], pageRows<Lead>());
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
      exportCsv<LifecycleRow>("门店生命周期", [
        { header: "类型", value: (l) => LC_KIND[l.kind].label },
        { header: "编号", value: (l) => l.no },
        { header: "名称", value: (l) => l.name },
        { header: "当前阶段", value: (l) => LC_PHASE[l.phase]?.label ?? l.phase },
        { header: "停留天数", value: (l) => (l.daysInPhase == null ? "" : String(l.daysInPhase)) },
        { header: "负责人", value: (l) => l.owner ?? "" },
      ], pageRows<LifecycleRow>());
    }
  }
  const onExport = q.data?.list?.length ? exportCurrent : undefined;
  const archivedToggle = <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />;
  const funnelLabel = (f: FunnelStage) => LC_PHASE[f.phase]?.label ?? f.phase;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={switchTab} />
      {tab === "venues" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地方名称" onExport={onExport}
          onAdd={canVenue ? () => setVenueForm({ locationCount: 0 }) : undefined} addLabel="新增场地方">
          {archivedToggle}
        </Toolbar>
      )}
      {tab === "contracts" && summary.data && (
        /* 摘要条的六个数都是**要人动手的事**；点一张卡 = 筛出那个待办子集（R2），再点取消 */
        <div className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-6">
          <FilterCard label="待我审批" value={summary.data.pendingMine}
            active={filters.pendingMine === "1"} onClick={() => toggleFilter("pendingMine", "1")} />
          {/* 后三张「不可点」：列表接口没有对应的筛选参数，做成按钮只会筛出一个不相干的集合 */}
          <SummaryCard label="待财务会签" value={summary.data.pendingCosign} />
          <SummaryCard label="终止待审批" value={summary.data.terminationPending} />
          <FilterCard label="60 天内到期" value={summary.data.expiring60}
            active={!!filters.endTo && filters.status === "ACTIVE"}
            onClick={() => {
              const on = !(filters.endTo && filters.status === "ACTIVE");
              const today = new Date().toISOString().slice(0, 10);
              const d60 = new Date(Date.now() + 60 * 86400_000).toISOString().slice(0, 10);
              setFilters((f) => ({ ...f, status: on ? "ACTIVE" : "", endFrom: on ? today : "", endTo: on ? d60 : "" }));
              paging.reset();
            }} />
          <FilterCard label="已到期未续" value={summary.data.expiredNotRenewed}
            active={filters.status === "EXPIRED"} onClick={() => toggleFilter("status", "EXPIRED")} />
          <SummaryCard label="缺签署件" value={summary.data.missingScan} />
        </div>
      )}
      {tab === "contracts" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索合同号 / 场地方 / 站点" onExport={onExport}
          onAdd={allow("location:contract:create") ? () => setContractForm({
            shareRate: 0.15, entryFee: 0,
            ...(siteNoParam ? { siteNo: siteNoParam, venueNo: sitesQ.data?.list.find((x) => x.siteNo === siteNoParam)?.venueNo ?? undefined } : {}),
          }) : undefined} addLabel="新增合同">
          <FilterSelect aria-label="状态" value={filters.status ?? ""} onChange={(v) => setFilter("status", v)}
            options={CONTRACT_STATUS} allLabel="全部状态" />
        </Toolbar>
      )}
      {tab === "crm" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索线索号 / 场地 / 负责人" onExport={onExport}
          onAdd={canCrm ? () => setLeadForm({ ownerType: "STAFF", expectSites: 1 }) : undefined} addLabel="新增线索">
          <FilterSelect aria-label="视图" value={filters.view || "ACTIVE"} onChange={(v) => setFilter("view", v === "ACTIVE" ? "" : v)}
            options={LEAD_VIEW} />
          <FilterSelect aria-label="阶段" value={filters.stage ?? ""} onChange={(v) => setFilter("stage", v)}
            options={LEAD_STAGE} allLabel="全部阶段" />
        </Toolbar>
      )}
      {siteNoParam && (
        <Notice>
          只看站点 <RefLink kind="site" no={siteNoParam} /> 的合同。站点要营业，须有一份生效中的合同 ——
          没有就「新增合同」绑定这个站点。
          <button className="ms-2 text-primary hover:underline" onClick={() => {
            const q2 = new URLSearchParams(sp.toString());
            q2.delete("siteNo");
            router.replace(`${pathname}?${q2.toString()}`, { scroll: false });
          }}>看全部合同</button>
        </Notice>
      )}
      {tab === "crm" && inPool && (
        <Notice>公共线索池：超过 30 天没人跟进的商机会被回收到这里（系统参数 lead.pool.recycle_days），负责人清空。谁认领谁负责，并重新计时。</Notice>
      )}
      {tab === "onboarding" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索场地名称 / 联系人" onExport={onExport}
          onAdd={allow("location:venue:create") ? () => setOnboardingForm({ status: "PENDING", industry: "购物中心" }) : undefined} addLabel="新增申请" />
      )}
      {tab === "lifecycle" && funnel.data && (
        /* 连续漏斗：签约前是商机五阶段，签约后是站点五状态；点一格筛下方列表（R2） */
        <div className="mb-3 grid grid-cols-2 gap-2 md:grid-cols-5 xl:grid-cols-10">
          {funnel.data.map((f) => (
            <FilterCard key={`${f.kind}:${f.phase}`} label={`${f.kind === "LEAD" ? "商机" : "站点"} · ${funnelLabel(f)}`}
              value={f.count} sub={f.avgDaysInPhase == null ? "—" : `平均停留 ${f.avgDaysInPhase.toFixed(1)} 天`}
              active={filters.phase === f.phase} onClick={() => toggleFilter("phase", f.phase)} />
          ))}
        </div>
      )}
      {tab === "lifecycle" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索编号 / 名称 / 负责人" onExport={onExport}>
          <FilterSelect aria-label="阶段" value={filters.phase ?? ""} onChange={(v) => setFilter("phase", v)}
            options={LC_PHASE} allLabel="全部阶段" />
        </Toolbar>
      )}
      {tab === "venues" && <DataTable rowKey={(v: Venue) => v.venueNo} columns={venueCols} rows={q.data?.list as Venue[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} rowClassName={archivedRowClass}
        empty={showArchived ? "没有匹配的场地方——换个关键词，或先「新增场地方」" : "没有在用的场地方——可能都已归档（打开「显示已归档」查看），或先「新增场地方」建档"} />}
      {tab === "contracts" && <DataTable rowKey={(c: Contract) => c.contractNo} columns={ctCols} rows={q.data?.list as Contract[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={Object.values(filters).some(Boolean) ? "没有符合筛选条件的合同——再点一次摘要卡或清空状态筛选" : "暂无合同——合同绑定「场地方 × 站点」，请先建好两者再「新增合同」，或从商机「签约转化」生成草稿"} />}
      {tab === "crm" && <DataTable rowKey={(l: Lead) => l.leadNo} columns={leadCols} rows={q.data?.list as Lead[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty={inPool ? "线索池是空的——没有超期未跟进的商机被回收" : "暂无线索——BD 拓展的场地线索会出现在这里，可点「新增线索」手工录入"} />}
      {tab === "onboarding" && <DataTable rowKey={(o: VenueOnboarding) => o.onboardingNo} columns={onboardingCols} rows={q.data?.list as VenueOnboarding[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无入驻申请——门店自助提交的申请会进入此列表待审核，也可点「新增申请」代录" />}
      {tab === "lifecycle" && <DataTable rowKey={(l: LifecycleRow) => `${l.kind}:${l.no}`} columns={lifecycleCols} rows={q.data?.list as LifecycleRow[]} loading={q.isLoading} error={q.error} onRetry={q.refetch}
        empty="暂无商机与站点——漏斗由「BD 拓展 CRM」的线索和「站点管理」的站点拼成，两者都为空时此处为空" />}
      {q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      {/* 进件驳回：只收一个原因 */}
      <FormDrawer
        open={!!rejectForm}
        onOpenChange={(o) => !o && setRejectForm(null)}
        titleNew=""
        titleEdit={`驳回申请 ${rejectForm?.onboardingNo ?? ""}`}
        isEdit
        fields={REJECT_FIELDS}
        value={(rejectForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRejectForm(v as { onboardingNo: string; note: string })}
        onSubmit={() => {
          if (!rejectForm?.note?.trim()) return;   // 服务端也拦；这里先省一次往返
          reviewOnboarding.mutate({ no: rejectForm.onboardingNo, approve: false, note: rejectForm.note });
          setRejectForm(null);
        }}
        submitting={reviewOnboarding.isPending}
      />

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

      <FormDrawer
        open={!!contractForm}
        onOpenChange={(o) => !o && setContractForm(null)}
        titleNew="新增合同（草稿）"
        titleEdit={`编辑合同草稿 ${contractForm?.contractNo ?? ""}`}
        isEdit={!!contractForm?.contractNo}
        fields={contractFields}
        value={(contractForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setContractForm(v as Partial<Contract>)}
        onSubmit={() => contractForm && submitContract()}
        submitting={saveContract.isPending}
      />

      <FormDrawer
        open={!!onboardingForm}
        onOpenChange={(o) => !o && setOnboardingForm(null)}
        titleNew="新增 Onboarding 申请"
        titleEdit={`编辑申请 ${onboardingForm?.onboardingNo ?? ""}`}
        isEdit={!!onboardingForm?.onboardingNo}
        fields={ONBOARDING_FIELDS}
        value={(onboardingForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setOnboardingForm(v as Partial<VenueOnboarding>)}
        onSubmit={() => onboardingForm && saveOnboarding.mutate(onboardingForm)}
        submitting={saveOnboarding.isPending}
      />

      <FormDrawer
        open={!!leadForm}
        onOpenChange={(o) => !o && setLeadForm(null)}
        titleNew="新增线索"
        titleEdit={`编辑商机档案 ${leadForm?.leadNo ?? ""}`}
        isEdit={!!leadForm?.leadNo}
        fields={leadFields}
        value={(leadForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setLeadForm(v as LeadSaveReq)}
        onSubmit={() => leadForm && saveLead.mutate(leadForm)}
        submitting={saveLead.isPending}
        width="w-[520px]"
      />

      {/* —— 详情抽屉：按当前 tab 与 ?no= 打开 —— */}
      <ContractDetailDrawer
        contractNo={tab === "contracts" ? detailNo : null}
        onOpenChange={(o) => !o && closeDetail()}
        onEdit={(c) => setContractForm(c)}
        onAttach={openAttach}
      />
      <LeadDetailDrawer
        leadNo={tab === "crm" ? detailNo : null}
        onOpenChange={(o) => !o && closeDetail()}
        onEdit={(l) => setLeadForm(leadToForm(l))}
      />
      <OnboardingDetailDrawer
        onboardingNo={tab === "onboarding" ? detailNo : null}
        onOpenChange={(o) => !o && closeDetail()}
        actions={onboardingActions}
      />
      {contractActions.ui}

      {/* 合同扫描件：先经文件服务上传（选文件即传），再「挂到合同上」—— 没点挂上的文件不算合同附件 */}
      <Drawer
        open={!!attachRow}
        onOpenChange={(o) => !o && setAttachRow(null)}
        title={attachRow ? `合同扫描件 ${attachRow.contractNo}` : ""}
        desc="盖章扫描件是对账时的凭据。已到期或已终止的合同不再收附件"
        width="w-[560px]"
        footer={attachRow && allow("location:contract:update") && (<>
          <Button variant="outline" onClick={() => setAttachRow(null)}>关闭</Button>
          <Button disabled={addAttach.isPending || !attachFiles.length}
            onClick={() => addAttach.mutate({ contractNo: attachRow.contractNo, fileNos: attachFiles })}>
            挂到合同上{attachFiles.length ? `（${attachFiles.length}）` : ""}
          </Button>
        </>)}
      >
        {attachRow && (<>
          <Field label="合同">{attachRow.venueName} · {attachRow.siteName}</Field>
          <Field label="已挂的扫描件">
            {attachRow.attachments.length === 0 ? (
              <span className="text-muted-foreground">还没有扫描件——合同签回后请把扫描件挂在这里，便于对账时追溯分成口径</span>
            ) : (
              <ul className="space-y-2">
                {attachRow.attachments.map((a) => (
                  <li key={a.attachNo} className="flex items-center gap-2 rounded-field bg-muted px-3 py-2">
                    <div className="min-w-0 flex-1">
                      {a.fileNo ? <FileLink fileNo={a.fileNo} label={a.fileName} /> : <div className="truncate txt-body">{a.fileName}</div>}
                      <div className="txt-caption text-muted-foreground tabular-nums">
                        {a.attachNo} · {fileSize(a.size)} · {a.uploadedBy} · {fmtTime(a.uploadedAt)}
                      </div>
                    </div>
                    {allow("location:contract:update") && (
                      <Button size="sm" variant="outline" disabled={removeAttach.isPending}
                        onClick={async () => {
                          if (await confirm({ title: "移除附件", desc: `确认移除「${a.fileName}」？移除会留痕，但合同上不再显示它。`, confirmText: "移除", danger: true })) {
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
          {allow("location:contract:update") ? (
            <Field label={`上传（${FILE_CATEGORY_RULES.CONTRACT_SCAN.accept}，单份不超过 ${FILE_CATEGORY_RULES.CONTRACT_SCAN.maxMb}MB）`}>
              <FileField value={attachFiles} onChange={setAttachFiles} category="CONTRACT_SCAN" max={5} />
            </Field>
          ) : (
            <ReadOnlyNotice className="mt-4" what="合同维护" perm="location:contract:update" note="不能上传或移除扫描件" />
          )}
        </>)}
      </Drawer>

      {dialog}
    </div>
  );
}

export default function VenuesPage() {
  return <Suspense fallback={null}><VenuesInner /></Suspense>;
}
