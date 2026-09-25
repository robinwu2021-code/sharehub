"use client";

// 工单闭环（G6）：开单 → 派单 → 接单 → 处理 → 完工 → （复核）→ 验收关单，全程留痕。
// 状态机定义在 lib/types/workorder.ts（WO_TRANSITIONS），页面按钮与 mock/后端校验共用同一份；
// 页面只负责「不给点非法动作」，真正的拒绝在服务端（mock 层抛 WorkOrderTransitionError）。
//
// 2026-09-25 批次 7b（方案 §8.4–8.7）：
//   · 列表：可点击摘要条（R2）、SLA 剩余列、关联告警数、来源 RefLink（R3）、`?no=` 深链打开详情；
//   · 详情抽屉：DetailHeader + 步骤条 + StateActions（状态只由动作改，R1）、关联告警与复核、时间线、照片；
//   · 派单候选人取后端（替换写死的 STAFF）、完工按类型必填（照片 / 故障原因 / 清点数 / 成本）；
//   · 列表页内的两个子视图：抢单池、成本汇总（不开新菜单 —— 菜单改动要走库迁移）。
import { Suspense, useMemo, useState, type ReactNode } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Input, Select } from "@/components/ui/input";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs } from "@/components/ui/tabs";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { StatusBadge, statusOptions } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { StateActions, type ActionSpec } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";
import { WoStatusBadge, EnabledBadge, WO_TYPE_LABEL } from "@/components/status";
import { WoSummaryBar, summaryFilter, type WoSummaryKey } from "@/components/workorder/wo-summary-bar";
import { SlaRemain } from "@/components/workorder/sla-remain";
import { WoDetailDrawer } from "@/components/workorder/wo-detail-drawer";
import { WoAssignDrawer } from "@/components/workorder/wo-assign-drawer";
import { WoHandleDrawer } from "@/components/workorder/wo-handle-drawer";
import { WoCloseDrawer } from "@/components/workorder/wo-close-drawer";
import { WoDeriveDrawer } from "@/components/workorder/wo-derive-drawer";
import { WoPoolView } from "@/components/workorder/wo-pool-view";
import { WoCostsView } from "@/components/workorder/wo-costs-view";
import { PRIO, REVIEW, SOURCE_LABEL } from "@/components/workorder/wo-meta";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import {
  WO_TRANSITIONS, canTransition, inspectionPeriodKey, inspectionRunnable, woDeriveBlocked, woTakeoverBlocked,
  woSlaRemain, fmtMinutes,
} from "@/lib/types";
import type {
  WorkOrder, WorkOrderAction, WorkOrderDraft, WorkOrderStatus,
  SlaRule, InspectionPlan, InspectionFrequency,
} from "@/lib/types";

/**
 * 状态的列/标签/筛选项**同一份**（下面三处都从它派生）。
 *
 * ⚠️ 少一档的代价是三重的，而且都不报错：看板上那些单子**整列消失**
 * （渲染按 `status === col.key` 精确匹配），列表里状态显示成英文原文
 * （`WO_STATUS_LABEL` 找不到就 `?? s` 回落），筛选下拉里也选不到。
 * ACCEPTED 此前就不在这儿 —— 真后端接单后工单正是这个状态。
 *
 * AUDITED 有意不列：后端 /close 一次走完 AUDIT→CLOSE，它只是事务内的过程态，
 * 从不落库（见 WoOpsServiceImpl.close）。列出来就是一列永远为空的看板。
 */
const BOARD_COLS: { key: string; label: string }[] = [
  { key: "CREATED", label: "待派单" }, { key: "DISPATCHED", label: "已派单" },
  { key: "ACCEPTED", label: "已接单" },
  { key: "PROCESSING", label: "处理中" }, { key: "DONE", label: "已完成" }, { key: "CLOSED", label: "已关闭" },
];
// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 本页用的是 `?view=` 而不是 `?tab=`，菜单里那四条叶子也是 view —— 两边必须一致。
// 抢单池 / 成本汇总不进这里：它们是「工单列表」下的子视图（`?sub=`），开成 tab 就得加菜单叶，而菜单改动要走库迁移。
const TAB_KEYS = ["list", "board", "sla", "inspection"] as const;
type Sub = "all" | "pool" | "costs";

const ACTION_LABEL: Record<WorkOrderAction, string> = {
  dispatch: "派单", accept: "接单", process: "提交处理", complete: "完工", close: "验收", reject: "驳回", rework: "退回返工",
};
/** 动作 → 所需权限码（列表 / 看板 / 详情共用一份）。 */
const PERM_OF: Record<WorkOrderAction, string> = {
  dispatch: "workorder:wo:dispatch", reject: "workorder:wo:dispatch",
  accept: "workorder:wo:handle", process: "workorder:wo:handle", complete: "workorder:wo:handle",
  close: "workorder:wo:close", rework: "workorder:wo:close",
};
/**
 * 每个状态下推进流程的那一步 = 主动作（按钮）；其余收进「更多」。
 * ACCEPTED 此前没有主动作 —— 接完单的工单在列表上只剩一个「⋯」，没人看得出下一步是到场提交处理。
 */
const PRIMARY_ACTION: Partial<Record<WorkOrderStatus, WorkOrderAction>> = {
  CREATED: "dispatch", DISPATCHED: "accept", ACCEPTED: "process", PROCESSING: "complete", DONE: "close",
};
const OPEN_STATUSES: WorkOrderStatus[] = ["DISPATCHED", "ACCEPTED", "PROCESSING"];

/** 状态中文名：与列表 Badge、看板列头同一份口径 */
const WO_STATUS_LABEL = (s: string) => BOARD_COLS.find((c) => c.key === s)?.label ?? s;
const slaText = (w: WorkOrder) => {
  const r = woSlaRemain(w);
  return r == null ? "" : r < 0 ? `超 ${fmtMinutes(r)}` : `剩 ${fmtMinutes(r)}`;
};

/** 导出列与表格可见列一致（操作列除外）。 */
const WO_CSV_COLS: CsvColumn<WorkOrder>[] = [
  { header: "工单号", value: (w) => w.woNo },
  { header: "类型", value: (w) => WO_TYPE_LABEL[w.type] },
  { header: "来源", value: (w) => `${SOURCE_LABEL[w.source]}${w.sourceNo ? ` · ${w.sourceNo}` : ""}` },
  { header: "柜机", value: (w) => w.cabinetNo },
  { header: "点位", value: (w) => w.locationName },
  { header: "优先级", value: (w) => PRIO[w.priority]?.label ?? w.priority },
  { header: "状态", value: (w) => WO_STATUS_LABEL(w.status) },
  { header: "处理人", value: (w) => w.handlerName ?? w.assigneeName ?? "未派单" },
  { header: "SLA", value: slaText },
  { header: "关联告警", value: (w) => w.ops?.alarmCount ?? 0 },
  { header: "创建", value: (w) => fmtTime(w.createdAt) },
];

/** 巡检频率的中文标签。**值是后端枚举，中文只在展示层出现。** */
const INSPECT_FREQ: Record<InspectionFrequency, string> = {
  DAILY: "每日", WEEKLY: "每周", BIWEEKLY: "双周", MONTHLY: "每月",
};

const WO_TYPE_OPTIONS = Object.entries(WO_TYPE_LABEL).map(([value, label]) => ({ value, label }));
// 优先级选项与徽标同源（含形状阶梯）：表单里选的形状 = 表格里看到的形状
const PRIO_OPTIONS = statusOptions(PRIO);
// 状态筛选项与看板列头同一份文案，不另抄一遍
const WO_STATUS_OPTIONS = BOARD_COLS.map((c) => ({ value: c.key, label: c.label }));
const SOURCE_OPTIONS = Object.entries(SOURCE_LABEL).map(([value, label]) => ({ value, label }));

const SLA_FIELDS: FieldDef[] = [
  { key: "slaNo", label: "SLA 编号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "woType", label: "工单类型", type: "select", options: WO_TYPE_OPTIONS },
  { key: "responseMins", label: "响应时限（分钟）", type: "number" },
  { key: "resolveMins", label: "解决时限（分钟）", type: "number" },
  { key: "escalateTo", label: "升级至", placeholder: "运维主管" },
  { key: "active", label: "启用", type: "switch" },
];
const INSPECTION_FIELDS: FieldDef[] = [
  { key: "planNo", label: "计划编号", readOnlyOnEdit: true, placeholder: "自动生成" },
  { key: "route", label: "巡检路线", placeholder: "市中心 A 线" },
  // 值是后端枚举、label 才是中文 —— 此前 value 也存中文，而周期键按枚举判。
  { key: "frequency", label: "频率", type: "select", options: [
    { value: "DAILY", label: "每日" }, { value: "WEEKLY", label: "每周" },
    { value: "BIWEEKLY", label: "双周" }, { value: "MONTHLY", label: "每月" },
  ] },
  { key: "nextAt", label: "下次巡检（ISO 时间）", placeholder: "2026-07-20T09:00:00Z" },
  { key: "assignee", label: "负责人", placeholder: "Ali" },
  { key: "active", label: "启用", type: "switch" },
];

const NEW_WO: Partial<WorkOrderDraft> = {
  type: "FAULT", priority: "MEDIUM", cabinetNo: "", locationName: "", description: "", expectedAt: "",
};

/** 列表「来源」列：业务号能跳就跳（R3）。告警来源的 sourceNo 在真后端是合并键（ALM:柜号:类型），不是告警号。 */
function SourceCell({ w }: { w: WorkOrder }) {
  const label = SOURCE_LABEL[w.source] ?? w.source;
  const no = w.sourceNo;
  let ref: ReactNode = null;
  if (no && w.source === "ALERT" && !no.includes(":")) ref = <RefLink kind="alarm" no={no} />;
  else if (no && w.source === "INSPECTION") ref = <RefLink kind="wo" no={no.split(":")[0]} />;
  else if (no && w.source !== "ALERT") ref = <span className="tabular-nums">{no}</span>;
  return <span className="text-muted-foreground">{label}{ref && <> · {ref}</>}</span>;
}

function WorkOrdersInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const paging = usePaging();
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const tabs = useNavTabs("/work-orders", TAB_KEYS);
  const { tab: view, setTab: setView } = usePageTab(tabs, () => { paging.reset(); setSelected([]); }, { param: "view" });
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [priority, setPriority] = useState("");
  const [source, setSource] = useState("");
  const [siteNo, setSiteNo] = useState("");
  const [summary, setSummary] = useState<WoSummaryKey | null>(null);
  const { confirm, dialog } = useConfirm();
  // 列表批量选中（G3）。翻页/切视图/改筛选都要清空——否则会对「看不见的行」下手。
  const [selected, setSelected] = useState<string[]>([]);
  const [batchAssignee, setBatchAssignee] = useState("");
  const clearSel = () => setSelected([]);
  const refilter = () => { paging.reset(); clearSel(); };
  // 翻页要清掉勾选：第 2 页留着第 1 页的勾选，批量操作会作用到看不见的行上
  const goPage = (p: number) => { paging.setPage(p); clearSel(); };

  // —— URL：`?no=` 详情深链（RefLink 的 wo 路由就指向这里）、`?sub=` 列表子视图 ——
  const setParam = (k: string, v: string | null) => {
    const q = new URLSearchParams(sp.toString());
    if (v) q.set(k, v); else q.delete(k);
    router.replace(q.size ? `${pathname}?${q.toString()}` : pathname, { scroll: false });
  };
  const detailNo = sp.get("no");
  const openDetail = (no: string) => setParam("no", no);
  const closeDetail = () => setParam("no", null);

  const canCreate = allow("workorder:wo:create");
  const canDispatch = allow("workorder:wo:dispatch");
  const canHandle = allow("workorder:wo:handle");
  const canClose = allow("workorder:wo:close");
  const canSla = allow("workorder:sla:update");
  const canInspection = allow("workorder:inspection:update");
  // 「立即执行一次」= 改计划留痕 + 开工单，两件事都做，故两个权限码都要有（不新造权限码）
  const canRunPlan = canInspection && canCreate;
  const canAny = canCreate || canDispatch || canHandle || canClose;

  // 子视图：抢单池要 wo:handle 才看得到（后端 pool 端点的码），成本汇总跟列表同码
  const subs = useMemo(() => [
    { key: "all", label: "全部工单" },
    ...(canHandle ? [{ key: "pool", label: "抢单池" }] : []),
    { key: "costs", label: "成本汇总" },
  ], [canHandle]);
  const rawSub = sp.get("sub");
  const sub: Sub = subs.some((s) => s.key === rawSub) ? (rawSub as Sub) : "all";

  // —— 抽屉状态 ——
  const [woForm, setWoForm] = useState<Partial<WorkOrderDraft> | null>(null);
  const [assign, setAssign] = useState<{ wo: WorkOrder; mode: "dispatch" | "takeover" } | null>(null);
  const [handle, setHandle] = useState<{ wo: WorkOrder; action: "process" | "complete" } | null>(null);
  const [closing, setClosing] = useState<WorkOrder | null>(null);
  const [rejecting, setRejecting] = useState<WorkOrder | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  // 同一个「填原因」抽屉服务两个动作：reject 驳回退回待派单 / rework 验收不合格退回返工
  const [rejectMode, setRejectMode] = useState<"reject" | "rework">("reject");
  const [deriveFrom, setDeriveFrom] = useState<WorkOrder | null>(null);

  const [slaKw, setSlaKw] = useState("");
  const [inspKw, setInspKw] = useState("");
  const [slaForm, setSlaForm] = useState<Partial<SlaRule> | null>(null);
  const [inspForm, setInspForm] = useState<Partial<InspectionPlan> | null>(null);

  // 摘要卡是筛选的一种：它给的 status / slaState / reviewStatus 覆盖下拉里的状态
  const filters = {
    keyword, type: type || undefined, status: status || undefined, priority: priority || undefined,
    source: source || undefined, siteNo: siteNo || undefined, ...summaryFilter(summary),
  };

  const summaryQ = useQuery({ queryKey: ["wo-summary"], queryFn: () => api.woSummary(), enabled: view === "list" && sub === "all" });
  const list = useQuery({
    queryKey: ["workorders", paging.page, paging.size, filters],
    queryFn: () => api.listWorkOrders({ page: paging.page, size: paging.size, ...filters }),
    placeholderData: keepPreviousData, enabled: view === "list" && sub === "all",
  });
  const board = useQuery({
    queryKey: ["workorders-board", keyword, type],
    queryFn: () => api.listWorkOrders({ page: 1, size: UNPAGED_SIZE, keyword, type: type || undefined }),
    enabled: view === "board",
  });
  const sla = useQuery({
    queryKey: ["sla-rules", paging.page, paging.size, slaKw],
    queryFn: () => api.listSlaRules({ page: paging.page, size: paging.size, keyword: slaKw }),
    placeholderData: keepPreviousData, enabled: view === "sla",
  });
  const inspection = useQuery({
    queryKey: ["inspection-plans", paging.page, paging.size, inspKw],
    queryFn: () => api.listInspectionPlans({ page: paging.page, size: paging.size, keyword: inspKw }),
    placeholderData: keepPreviousData, enabled: view === "inspection",
  });
  // 开单时机柜下拉：与设备台账同一份数据，避免手打错柜号
  const cabinetOpts = useQuery({
    queryKey: ["wo-cabinet-options"],
    queryFn: () => api.listCabinets({ page: 1, size: UNPAGED_SIZE }),
    enabled: !!woForm,
  });
  // 站点筛选的选项：站点表有界（以运营站点数为上界），一次取完；没有站点查看权限就不出这个筛选
  const canSites = allow("location:poi:read");
  const siteOpts = useQuery({
    queryKey: ["wo-site-options"],
    queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }),
    enabled: view === "list" && sub === "all" && canSites,
    staleTime: 5 * 60_000,
  });
  // 批量派单的处理人：与派单抽屉同一个候选人接口（不带站点 = 没有责任人置顶）
  const batchCands = useQuery({
    queryKey: ["wo-candidates", ""],
    queryFn: () => api.assigneeCandidates(),
    enabled: selected.length > 0 && canDispatch,
  });
  const batchPick = batchAssignee || batchCands.data?.[0]?.no || "";

  const refreshWo = () => {
    for (const k of ["workorders", "workorders-board", "wo-summary", "wo-detail", "wo-pool", "wo-costs"]) {
      qc.invalidateQueries({ queryKey: [k] });
    }
  };
  /** 流转成功统一收口：提示 + 刷新；失败走全局 MutationCache.onError（含非法迁移报错）。 */
  const done = (after: () => void) => (msg: string) => { notify.success(msg); refreshWo(); after(); };

  const doCreate = useMutation({
    mutationFn: (v: WorkOrderDraft) => api.createWorkOrder(v),
    onSuccess: () => done(() => setWoForm(null))("工单已创建"),
  });
  const doAccept = useMutation({
    mutationFn: (no: string) => api.acceptWorkOrder(no),
    onSuccess: () => done(() => undefined)("已接单；到场后点「提交处理」进入处理中"),
  });
  const doReject = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.rejectWorkOrder(v.no, v.reason),
    onSuccess: () => done(() => setRejecting(null))("已驳回，工单退回待派单"),
  });
  const doRework = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.reworkWorkOrder(v.no, v.reason),
    onSuccess: () => done(() => setRejecting(null))("已退回返工，工单回到处理中"),
  });

  // —— 批量派单（G3）——
  // 工单有状态机：只有 CREATED（待派单）能派单，其余状态服务端会拒。
  // 因此批量前先过滤，并把「跳过几张」写进确认文案——否则用户只会看到一串报错。
  const batchDispatch = useMutation({
    mutationFn: (v: { nos: string[]; assignee: string }) =>
      Promise.all(v.nos.map((no) => api.dispatchWorkOrder(no, v.assignee))),
    onSuccess: (_r, v) => { refreshWo(); notify.success(`已把 ${v.nos.length} 张工单派给 ${v.assignee}`); clearSel(); },
  });
  const askBatchDispatch = async () => {
    const rowsOnPage = list.data?.list ?? [];
    const picked = rowsOnPage.filter((w) => selected.includes(w.woNo));
    const eligible = picked.filter((w) => w.status === "CREATED");
    const skipped = picked.length - eligible.length;
    if (eligible.length === 0) {
      notify.error(`已选 ${picked.length} 张工单均不处于「待派单」，无法派单；请先点摘要条「待派单」再选`);
      return;
    }
    const name = batchCands.data?.find((c) => c.no === batchPick)?.name ?? batchPick;
    const ok = await confirm({
      title: `批量派单 ${eligible.length} 张`,
      desc: `已选 ${picked.length} 张工单，其中 ${eligible.length} 张处于「待派单」可派给 ${name}`
        + (skipped > 0 ? `，另 ${skipped} 张状态不符将跳过。` : "。")
        + "批量派单不看站点责任人；要按责任人派，请逐张打开派单抽屉。",
      confirmText: `确认派给 ${name}`,
    });
    if (ok) batchDispatch.mutate({ nos: eligible.map((w) => w.woNo), assignee: batchPick });
  };

  const openAction = (w: WorkOrder, a: WorkOrderAction) => {
    if (a === "dispatch") { setAssign({ wo: w, mode: "dispatch" }); return; }
    if (a === "accept") { doAccept.mutate(w.woNo); return; }
    if (a === "process" || a === "complete") { setHandle({ wo: w, action: a }); return; }
    if (a === "close") { setClosing(w); return; }
    setRejectMode(a === "rework" ? "rework" : "reject");
    setRejecting(w); setRejectReason("");
  };

  const busy = doAccept.isPending || doReject.isPending || doRework.isPending;

  /**
   * 当前工单的全部动作（StateActions 描述）。**列表行、看板卡片、详情头共用这一份**：
   * 合法与否按迁移表（when），缺权限渲染禁用 + 提示缺哪个码（不静默隐藏），
   * 业务上暂不可用的（派生 / 接管）也渲染禁用并写明原因。
   */
  const actionsOf = (w: WorkOrder): ActionSpec[] => {
    const specs: ActionSpec[] = (Object.keys(WO_TRANSITIONS) as WorkOrderAction[]).map((a) => ({
      key: a,
      label: ACTION_LABEL[a],
      perm: PERM_OF[a],
      when: canTransition(w.status, a),
      primary: PRIMARY_ACTION[w.status] === a,
      danger: a === "reject" || a === "rework",
      blockedReason: busy ? "处理中…" : null,
      // 接单没有抽屉可兜底，是直接生效的动作 —— 要确认（R4）
      confirm: a === "accept"
        ? { title: `接单 ${w.woNo}`, desc: "接单即响应：SLA 响应计时到此为止，之后到场提交处理。", confirmText: "确认接单" }
        : undefined,
      onRun: () => openAction(w, a),
    }));
    specs.push({
      key: "derive", label: "派生子单", perm: "workorder:wo:handle",
      when: w.type === "INSPECT" && w.status !== "CLOSED" && w.status !== "AUDITED",
      blockedReason: woDeriveBlocked(w),
      onRun: () => setDeriveFrom(w),
    });
    specs.push({
      key: "takeover", label: "平台接管", perm: "workorder:wo:dispatch",
      // 只有代理承接的未完工单才谈得上接管；SLA 未超时时按钮在、但禁用并写明「剩多久」
      when: w.ops?.assigneeType === "AGENT" && OPEN_STATUSES.includes(w.status),
      blockedReason: woTakeoverBlocked(w),
      onRun: () => setAssign({ wo: w, mode: "takeover" }),
    });
    return specs;
  };

  const WO_FIELDS: FieldDef[] = useMemo(() => {
    const cabs = cabinetOpts.data?.list ?? [];
    return [
      { key: "type", label: "工单类型", type: "select", options: WO_TYPE_OPTIONS, required: true, section: "基本信息" },
      {
        key: "cabinetNo", label: "机柜号", type: "select", required: true, section: "基本信息",
        options: [{ value: "", label: "请选择机柜" }, ...cabs.map((c) => ({ value: c.cabinetNo, label: `${c.cabinetNo} · ${c.locationName}` }))],
        help: cabinetOpts.isLoading ? "机柜列表加载中…" : "与设备台账同一份数据",
      },
      { key: "locationName", label: "站点", maxLength: 40, section: "基本信息", placeholder: "留空则按机柜自动带出", help: "仅在机柜刚移机、台账未同步时才需手填" },
      { key: "priority", label: "优先级", type: "select", options: PRIO_OPTIONS, required: true, section: "基本信息" },
      { key: "description", label: "问题描述", type: "textarea", rows: 4, required: true, maxLength: 200, section: "问题与时限", placeholder: "现象、影响面、已做过的排查" },
      { key: "expectedAt", label: "期望完成时间", type: "date", section: "问题与时限", help: "超期工单会在看板与 SLA 统计里标红" },
    ];
  }, [cabinetOpts.data, cabinetOpts.isLoading]);

  const saveSla = useMutation({
    mutationFn: (v: Partial<SlaRule>) => api.saveSlaRule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["sla-rules"] }); notify.success(t("common.success")); setSlaForm(null); },
  });
  // 编辑前取单条最新值：列表可能是几分钟前的，拿旧行覆盖会把别人刚改的时限冲掉
  const editSla = useMutation({
    mutationFn: (no: string) => api.getSlaRule(no),
    onSuccess: (r) => setSlaForm(r),
  });
  // 「立即执行一次」：生成的工单会真的进列表/看板，故成功后连工单查询一起失效。
  const runPlan = useMutation({
    mutationFn: (no: string) => api.runInspectionPlan(no),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["inspection-plans"] });
      refreshWo();
      notify.success(
        `巡检计划 ${r.planNo} 已执行（周期 ${r.period}）：生成 ${r.woNos.length} 张巡检工单 ${r.woNos.join("、")}，已派给计划负责人`,
      );
    },
  });
  /** 执行前二次确认：说清开几张、派给谁、计入哪个周期（幂等口径要让人看得见）。 */
  const askRunPlan = async (pl: InspectionPlan) => {
    const blocked = inspectionRunnable(pl);
    if (blocked) { notify.error(`巡检计划 ${pl.planNo} 无法执行：${blocked}`); return; }
    const stops = pl.route.split("→").map((s) => s.trim()).filter(Boolean);
    const ok = await confirm({
      title: `立即执行巡检计划 ${pl.planNo}`,
      desc: `将按路线「${pl.route}」为 ${stops.length} 个站点各开一张巡检工单，并派给 ${pl.assignee}。`
        + `本次计入周期 ${inspectionPeriodKey(pl.frequency)}——同周期内再点会被拒绝，不会重复开单。`
        + `计划的「下次巡检」时间不变（手动补跑不推进排期）。`,
      confirmText: "确认执行",
    });
    if (ok) runPlan.mutate(pl.planNo);
  };

  const saveInspection = useMutation({
    mutationFn: (v: Partial<InspectionPlan>) => api.saveInspectionPlan(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["inspection-plans"] }); notify.success(t("common.success")); setInspForm(null); },
  });

  const cols: Column<WorkOrder>[] = [
    // 业务号列 body-strong（类型阶 txt-strong = 14/500）作扫描锚点（规范 §12.3）；点开详情
    {
      header: "工单号",
      cell: (w) => (
        <button type="button" className="txt-strong tabular-nums underline-offset-2 hover:underline" onClick={() => openDetail(w.woNo)}>{w.woNo}</button>
      ),
    },
    { header: "类型", cell: (w) => WO_TYPE_LABEL[w.type] ?? w.type },
    { header: "来源", cell: (w) => <SourceCell w={w} /> },
    { header: "柜机 / 点位", cell: (w) => <span className="text-muted-foreground">{w.cabinetNo ?? "-"} · {w.locationName ?? "-"}</span> },
    // nowrap：形状标记让文案变宽，窄列里会把「▲▲ 紧急」折成两行、把整行行高撑起来
    { header: "优先级", cell: (w) => <StatusBadge map={PRIO} value={w.priority} className="whitespace-nowrap" /> },
    {
      header: "状态",
      cell: (w) => (
        <div className="flex flex-wrap items-center gap-1">
          <WoStatusBadge s={w.status} />
          {w.ops?.reviewStatus === "FAILED" && w.status === "DONE" && <StatusBadge map={REVIEW} value="FAILED" />}
        </div>
      ),
    },
    {
      header: "处理人",
      cell: (w) => (
        <span className="text-muted-foreground">
          {w.handlerName ?? w.assigneeName ?? "未派单"}{w.ops?.assigneeType === "AGENT" && "（代理）"}
        </span>
      ),
    },
    { header: "SLA 剩余", cell: (w) => <SlaRemain w={w} /> },
    {
      header: "关联告警", className: "text-end",
      cell: (w) => <span className={w.ops?.alarmCount ? "tabular-nums" : "tabular-nums text-muted-foreground"}>{w.ops?.alarmCount ?? 0}</span>,
    },
    { header: "创建", cell: (w) => <span className="text-muted-foreground">{fmtTime(w.createdAt)}</span> },
    {
      header: t("common.actions"),
      // 主动作按钮 + 详情常驻，其余（驳回 / 退回返工 / 派生 / 接管…）进「更多」；缺权限的禁用并说明缺哪个码
      cell: (w) => (
        <div className="flex items-center gap-1.5">
          <StateActions actions={actionsOf(w)} />
          <Button size="sm" variant="outline" onClick={() => openDetail(w.woNo)}>详情</Button>
        </div>
      ),
    },
  ];

  const slaCols: Column<SlaRule>[] = [
    { header: "SLA 编号", cell: (s) => <span className="txt-strong tabular-nums">{s.slaNo}</span> },
    { header: "工单类型", cell: (s) => <Badge tone="outline">{WO_TYPE_LABEL[s.woType] ?? s.woType}</Badge> },
    // 时限是纯数量：右对齐 + 等宽，位数才对得齐（规范 §12.4）
    { header: "响应时限", className: "text-end", cell: (s) => <span className="tabular-nums">{s.responseMins} 分钟</span> },
    { header: "解决时限", className: "text-end", cell: (s) => <span className="tabular-nums">{s.resolveMins} 分钟</span> },
    { header: "升级至", cell: (s) => <span className="text-muted-foreground">{s.escalateTo}</span> },
    { header: "状态", cell: (s) => <EnabledBadge on={s.active} /> },
    {
      header: t("common.actions"),
      cell: (s) => canSla
        ? <Button size="sm" variant="outline" disabled={editSla.isPending} onClick={() => editSla.mutate(s.slaNo)}>{t("common.edit")}</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];
  const inspectionCols: Column<InspectionPlan>[] = [
    { header: "计划编号", cell: (p) => <span className="txt-strong tabular-nums">{p.planNo}</span> },
    { header: "巡检路线", cell: (p) => p.route },
    {
      header: "频率",
      cell: (p) => (
        <>
          <Badge tone="outline">{INSPECT_FREQ[p.frequency] ?? p.frequency}</Badge>
          {/* cron 是执行口径（后端原话）。两者不一致时，只看频率标签会以为
              计划按标签跑 —— 把真表达式摆出来，不一致一眼可见。 */}
          <div className="truncate txt-caption text-muted-foreground tabular-nums">{p.cron}</div>
        </>
      ),
    },
    { header: "下次巡检", cell: (p) => <span className="text-muted-foreground">{fmtTime(p.nextAt)}</span> },
    { header: "负责人", cell: (p) => <span className="text-muted-foreground">{p.assignee}</span> },
    { header: "状态", cell: (p) => <EnabledBadge on={p.active} /> },
    // 上次执行：没有它就看不出「这个计划今天到底跑没跑」，也解释不了按钮为什么变灰
    {
      header: "上次执行",
      cell: (p) => (
        <span className="text-muted-foreground">
          {p.lastRunAt ? `${fmtTime(p.lastRunAt)} · ${p.lastRunWoNos?.length ?? 0} 张工单` : "从未执行"}
        </span>
      ),
    },
    {
      header: t("common.actions"),
      cell: (p) => {
        // 不可执行的原因直接挂 title：按钮变灰但说得出为什么（判定与 mock 校验同一份）
        const blocked = inspectionRunnable(p);
        if (!canRunPlan && !canInspection) return <span className="text-muted-foreground">-</span>;
        return (
          <div className="flex flex-wrap items-center gap-1.5">
            {canRunPlan && (
              <Button
                size="sm" disabled={runPlan.isPending || !!blocked}
                title={blocked ?? "按路线各开一张巡检工单，并派给计划负责人"}
                onClick={() => void askRunPlan(p)}
              >立即执行一次</Button>
            )}
            {canInspection && <Button size="sm" variant="outline" onClick={() => setInspForm(p)}>{t("common.edit")}</Button>}
          </div>
        );
      },
    },
  ];

  const rows = board.data?.list ?? [];
  const siteOptions = (siteOpts.data?.list ?? []).map((s) => ({ value: s.siteNo, label: `${s.name}（${s.siteNo}）` }));

  return (
    <div>
      <TabHeader tabs={tabs} value={view} onChange={setView} />

      {view === "list" && (
        <Tabs tabs={subs} value={sub} onChange={(k) => { setParam("sub", k === "all" ? null : k); refilter(); }} />
      )}

      {view === "list" && sub === "pool" && <WoPoolView onOpen={openDetail} onChanged={refreshWo} />}
      {view === "list" && sub === "costs" && <WoCostsView />}

      {view === "list" && sub === "all" && (
        <WoSummaryBar data={summaryQ.data} loading={summaryQ.isLoading} active={summary}
          onPick={(k) => { setSummary(k); if (k) setStatus(""); refilter(); }} />
      )}

      {((view === "list" && sub === "all") || view === "board") && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); refilter(); }}
            searchPlaceholder="搜索工单号 / 柜机 / 来源单号"
            onAdd={canCreate ? () => setWoForm({ ...NEW_WO }) : undefined}
            addLabel="新建工单"
            onExport={view === "list" ? () => exportCsv<WorkOrder>("工单列表", WO_CSV_COLS, list.data?.list ?? []) : undefined}
            selectedCount={view === "list" ? selected.length : 0}
            batchActions={
              <>
                {/* 处理人与派单抽屉同一个候选人接口，不另造名单 */}
                <Select className="h-8" value={batchPick} onChange={(e) => setBatchAssignee(e.target.value)} aria-label="批量派单处理人">
                  {(batchCands.data ?? []).map((c) => <option key={c.no} value={c.no}>{c.name}（{c.no}）</option>)}
                </Select>
                <Button size="sm" disabled={batchDispatch.isPending || !batchPick} onClick={askBatchDispatch}>批量派单</Button>
              </>
            }
            onClearSelection={clearSel}
          >
            <FilterSelect value={type} onChange={(v) => { setType(v); refilter(); }}
              allLabel="全部类型" options={WO_TYPE_OPTIONS} aria-label="按工单类型筛选" />
            {view === "list" && (
              <>
                <FilterSelect value={status} onChange={(v) => { setStatus(v); setSummary(null); refilter(); }}
                  allLabel="全部状态" options={WO_STATUS_OPTIONS} aria-label="按工单状态筛选" />
                <FilterSelect value={priority} onChange={(v) => { setPriority(v); refilter(); }}
                  allLabel="全部优先级" options={PRIO} aria-label="按优先级筛选" />
                <FilterSelect value={source} onChange={(v) => { setSource(v); refilter(); }}
                  allLabel="全部来源" options={SOURCE_OPTIONS} aria-label="按来源筛选" />
                {canSites && (
                  <FilterSelect value={siteNo} onChange={(v) => { setSiteNo(v); refilter(); }}
                    allLabel="全部站点" options={siteOptions} aria-label="按站点筛选" />
                )}
              </>
            )}
          </Toolbar>
          {!canAny && (
            <ReadOnlyNotice
              what="工单开单/流转"
              perm={["workorder:wo:create", "workorder:wo:dispatch", "workorder:wo:handle", "workorder:wo:close"]}
              note="只能看列表与详情，不能开单、派单、处理或验收关单"
            />
          )}
        </>
      )}

      {view === "sla" && (
        <>
          <Toolbar
            search={slaKw}
            onSearch={(v) => { setSlaKw(v); paging.reset(); }}
            searchPlaceholder="搜索 SLA 编号 / 类型 / 升级对象"
            onAdd={canSla ? () => setSlaForm({ woType: "FAULT", responseMins: 30, resolveMins: 240, escalateTo: "", active: true }) : undefined}
            addLabel="新增 SLA"
            onExport={() => exportCsv<SlaRule>("SLA 管理", [
              { header: "SLA 编号", value: (s) => s.slaNo },
              { header: "工单类型", value: (s) => WO_TYPE_LABEL[s.woType] ?? s.woType },
              { header: "响应时限(分钟)", value: (s) => s.responseMins },
              { header: "解决时限(分钟)", value: (s) => s.resolveMins },
              { header: "升级至", value: (s) => s.escalateTo },
              { header: "状态", value: (s) => (s.active ? "启用" : "停用") },
            ], sla.data?.list ?? [])}
          />
          <DataTable rowKey={(s: SlaRule) => s.slaNo} columns={slaCols} rows={sla.data?.list} loading={sla.isLoading} error={sla.error} onRetry={sla.refetch}
            empty="暂无 SLA 规则——尚未配置响应/解决时限，工单不会触发超时升级；点右上「新增 SLA」建一条。" />
          {sla.data && <Pagination page={paging.page} size={paging.size} total={sla.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
        </>
      )}

      {view === "inspection" && (
        <>
          <Toolbar
            search={inspKw}
            onSearch={(v) => { setInspKw(v); paging.reset(); }}
            searchPlaceholder="搜索计划编号 / 路线 / 负责人"
            onAdd={canInspection ? () => setInspForm({ route: "", frequency: "WEEKLY", nextAt: "", assignee: "", active: true }) : undefined}
            addLabel="新增巡检计划"
            onExport={() => exportCsv<InspectionPlan>("巡检计划", [
              { header: "计划编号", value: (p) => p.planNo },
              { header: "巡检路线", value: (p) => p.route },
              { header: "频率", value: (p) => INSPECT_FREQ[p.frequency] ?? p.frequency },
              { header: "调度表达式", value: (p) => p.cron },
              { header: "下次巡检", value: (p) => fmtTime(p.nextAt) },
              { header: "负责人", value: (p) => p.assignee },
              { header: "状态", value: (p) => (p.active ? "启用" : "停用") },
            ], inspection.data?.list ?? [])}
          />
          {/* 说清「立即执行一次」到底做了什么、为什么同周期点不了第二次 */}
          <Notice>
            「立即执行一次」按路线真的生成巡检工单（来源「巡检计划」，在工单列表搜计划号即可找到），
            并派给计划负责人；同一计划同周期只能执行一次，重复点击会被拒绝。
            定时自动执行尚未上线（多副本要分布式锁），目前只能手动触发。
          </Notice>
          {!canRunPlan && !canInspection && <ReadOnlyNotice what="巡检计划维护/执行" perm={["workorder:inspection:update", "workorder:wo:create"]} note="不能新增、编辑或立即执行" />}
          <DataTable rowKey={(p: InspectionPlan) => p.planNo} columns={inspectionCols} rows={inspection.data?.list} loading={inspection.isLoading} error={inspection.error} onRetry={inspection.refetch}
            empty="暂无巡检计划——巡检工单目前只能手工开；点右上「新增巡检计划」按路线周期自动开单。" />
          {inspection.data && <Pagination page={paging.page} size={paging.size} total={inspection.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
        </>
      )}

      {view === "list" && sub === "all" && (
        <>
          <DataTable rowKey={(w: WorkOrder) => w.woNo} columns={cols} rows={list.data?.list} loading={list.isLoading} error={list.error} onRetry={list.refetch}
            selectable={canDispatch}
            selectedKeys={selected}
            onSelectedChange={setSelected}
            empty={summary
              ? "这个待办子集里没有工单——好消息；再点一次上面那张卡取消筛选，看全部工单。"
              : "没有符合条件的工单——可能是筛选条件太窄，或告警/投诉尚未转工单；放宽筛选，或点右上「新建工单」。"} />
          {list.data && <Pagination page={paging.page} size={paging.size} total={list.data.total} onPage={goPage} onSize={paging.setSize} />}
        </>
      )}

      {view === "board" && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 xl:grid-cols-6">
          {BOARD_COLS.map((col) => {
            const items = rows.filter((w) => w.status === col.key);
            return (
              <div key={col.key} className="rounded-card bg-muted/40 p-2">
                <div className="mb-2 flex items-center justify-between px-1 txt-strong">
                  <span>{col.label}</span>
                  <span className="txt-caption tabular-nums text-muted-foreground">{items.length}</span>
                </div>
                <div className="space-y-2">
                  {items.map((w) => (
                    <Card key={w.woNo} className="p-3">
                      <div className="flex items-center justify-between gap-2">
                        <button type="button" className="txt-strong tabular-nums underline-offset-2 hover:underline" onClick={() => openDetail(w.woNo)}>{w.woNo}</button>
                        <StatusBadge map={PRIO} value={w.priority} className="whitespace-nowrap" />
                      </div>
                      <div className="mt-1 flex flex-wrap items-center gap-1.5 txt-caption text-muted-foreground">
                        <Badge tone="outline">{WO_TYPE_LABEL[w.type] ?? w.type}</Badge>
                        <span>{w.cabinetNo}</span>
                      </div>
                      <div className="mt-1 line-clamp-2 txt-caption text-muted-foreground">{w.description}</div>
                      {/* SLA 标记：形状 + 颜色（▲ 超时 / ◆ 即将超时），与列表同一个组件 */}
                      <div className="mt-1 txt-caption"><SlaRemain w={w} /></div>
                      <div className="mt-2 flex items-center justify-between gap-2">
                        <span className="truncate txt-caption text-muted-foreground">{w.handlerName ?? w.assigneeName ?? "未派单"}</span>
                        {/* 看板不做拖拽（静态导出下拖拽库成本高收益低），用「下一步动作」按钮改状态 */}
                        <div className="flex shrink-0 items-center gap-1"><StateActions actions={actionsOf(w)} /></div>
                      </div>
                    </Card>
                  ))}
                  {items.length === 0 && <div className="px-1 py-4 text-center txt-caption text-muted-foreground">—</div>}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 开单 */}
      <FormDrawer
        open={!!woForm}
        onOpenChange={(o) => !o && setWoForm(null)}
        titleNew="新建工单"
        titleEdit="新建工单"
        isEdit={false}
        fields={WO_FIELDS}
        value={(woForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setWoForm(v as Partial<WorkOrderDraft>)}
        onSubmit={() => woForm && doCreate.mutate(woForm as WorkOrderDraft)}
        submitting={doCreate.isPending}
      />

      {/* 详情（?no= 深链）：头部动作与列表同一份 actionsOf */}
      <WoDetailDrawer woNo={detailNo} onClose={closeDetail} actions={actionsOf} />

      {/* 派单 / 平台接管：候选人取后端，责任人置顶 */}
      <WoAssignDrawer
        wo={assign?.wo ?? null}
        mode={assign?.mode ?? "dispatch"}
        onClose={() => setAssign(null)}
        onDone={done(() => setAssign(null))}
      />

      {/* 提交处理 / 完工：完工按类型必填 */}
      <WoHandleDrawer target={handle} onClose={() => setHandle(null)} onDone={done(() => setHandle(null))} />

      {/* 验收：顶部先给复核结果 */}
      <WoCloseDrawer
        wo={closing}
        onClose={() => setClosing(null)}
        onDone={done(() => setClosing(null))}
        onRework={canClose ? (w) => { setClosing(null); openAction(w, "rework"); } : undefined}
      />

      {/* 巡检派生 */}
      <WoDeriveDrawer
        parent={deriveFrom}
        onClose={() => setDeriveFrom(null)}
        onDone={(child) => done(() => setDeriveFrom(null))(`已派生 ${child.woNo}（${WO_TYPE_LABEL[child.type] ?? child.type}），进入待派单`)}
      />

      {/* 驳回退回 / 退回返工：原因为空则提交禁用 */}
      <Drawer
        open={!!rejecting}
        onOpenChange={(o) => !o && setRejecting(null)}
        title={`${rejectMode === "rework" ? "验收不合格退回返工" : "驳回"} ${rejecting?.woNo ?? ""}`}
        desc={rejectMode === "rework"
          ? "退回后工单回到「处理中」，处理人不变，无需重新派单"
          : "驳回后工单退回「待派单」，处理人被清空，需重新派单"}
        footer={
          rejecting && (
            <>
              <Button variant="outline" onClick={() => setRejecting(null)}>取消</Button>
              <Button
                variant="destructive"
                disabled={doReject.isPending || doRework.isPending || !rejectReason.trim()}
                onClick={() => (rejectMode === "rework" ? doRework : doReject).mutate({ no: rejecting.woNo, reason: rejectReason })}
              >{rejectMode === "rework" ? "确认退回返工" : "确认驳回"}</Button>
            </>
          )
        }
      >
        {rejecting && (
          <>
            <Field label="当前处理人">{rejecting.handlerName ?? rejecting.assigneeName ?? "-"}</Field>
            <Field label={rejectMode === "rework" ? "不合格原因（必填）" : "驳回原因（必填）"}>
              <Input value={rejectReason} placeholder={rejectMode === "rework" ? "说明哪里没修好，回写给处理人" : "说明退回理由，回写给派单人"}
                onChange={(e) => setRejectReason(e.target.value)} />
            </Field>
          </>
        )}
      </Drawer>

      <FormDrawer
        open={!!slaForm}
        onOpenChange={(o) => !o && setSlaForm(null)}
        titleNew="新增 SLA"
        titleEdit={`编辑 SLA ${slaForm?.slaNo ?? ""}`}
        isEdit={!!slaForm?.slaNo}
        fields={SLA_FIELDS}
        value={(slaForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setSlaForm(v as Partial<SlaRule>)}
        onSubmit={() => slaForm && saveSla.mutate(slaForm)}
        submitting={saveSla.isPending}
      />

      <FormDrawer
        open={!!inspForm}
        onOpenChange={(o) => !o && setInspForm(null)}
        titleNew="新增巡检计划"
        titleEdit={`编辑巡检计划 ${inspForm?.planNo ?? ""}`}
        isEdit={!!inspForm?.planNo}
        fields={INSPECTION_FIELDS}
        value={(inspForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setInspForm(v as Partial<InspectionPlan>)}
        onSubmit={() => inspForm && saveInspection.mutate(inspForm)}
        submitting={saveInspection.isPending}
      />

      {dialog}
    </div>
  );
}

export default function WorkOrdersPage() {
  return <Suspense fallback={null}><WorkOrdersInner /></Suspense>;
}
