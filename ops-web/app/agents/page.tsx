"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Input } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { exportCsv } from "@/lib/export-csv";
import { money, fmtTime } from "@/lib/utils";
import { useAuth } from "@/lib/auth";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { FilterSelect } from "@/components/ui/filter-select";
// 绩效周期复用报表域枚举：代理 GMV = 名下站点营收之和，必须与站点坪效同一套周期口径
import { REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod } from "@/lib/types";
import type {
  Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission, DataScope,
  AgentAssignmentRecord, AssignableAsset,
} from "@/lib/types";

/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;
// 数据范围文案与 app/employees 同源（台账 T5：AgentAccount.dataScope 原为 string
// 且 mock 里存的是中文展示文案，收紧为 DataScope 枚举后统一走映射渲染）
const SCOPE_LABEL: Record<DataScope, string> = { ALL: "全部数据", REGION: "按区域", LOCATION: "按点位", AGENT: "按代理(自己)", SELF: "仅自己经手" };
const SCOPE_OPTIONS = (["ALL", "REGION", "LOCATION", "AGENT", "SELF"] as DataScope[]).map((s) => ({ value: s, label: SCOPE_LABEL[s] }));
// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
// 注意「代理账号」在菜单里叫「代理账号管理」——以菜单为准，这里不再写第二份名字
const TAB_KEYS = ["profiles", "commission", "assign", "performance", "accounts"] as const;
const COMMISSION_FIELDS: import("@/components/ui/form-drawer").FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "agentNo", label: "代理编号", placeholder: "AG001" },
  { key: "agentName", label: "代理名称" },
  { key: "basis", label: "计佣基数", type: "select", options: [{ value: "GMV", label: "GMV" }, { value: "ORDER_COUNT", label: "订单量" }] },
  { key: "rate", label: "分润比例（0~1）", type: "number" },
  { key: "mode", label: "结算模式", type: "select", options: [{ value: "CHANNEL_SPLIT", label: "渠道分成" }, { value: "LEDGER", label: "账务分录" }] },
  { key: "effectiveAt", label: "生效日期", placeholder: "2026-01-01" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "INACTIVE", label: "停用" }] },
];
// —— 划拨（S1）——
/** multiselect + csv 的值是逗号分隔业务号串（与 employees 页数据权限抽屉同一套约定）。 */
const csvArr = (v: unknown) => String(v ?? "").split(",").map((s) => s.trim()).filter(Boolean);
/** 选项上标注当前归属：不标就会把别的代理的柜子误划走。 */
const assetLabel = (a: AssignableAsset) =>
  `${a.assetNo} · ${a.name}（当前：${a.currentAgentNo ? `${a.currentAgentNo} ${a.currentAgentName ?? ""}`.trim() : "平台直营"}）`;
const toOptions = (rows: AssignableAsset[] | undefined, type: AssignableAsset["assetType"]) =>
  (rows ?? []).filter((a) => a.assetType === type).map((a) => ({ value: a.assetNo, label: assetLabel(a) }));
const ASSET_TYPE_LABEL: Record<AssignableAsset["assetType"], string> = { CABINET: "机柜", SITE: "站点" };

const ACCOUNT_FIELDS: FieldDef[] = [
  { key: "accountNo", label: "账号编号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "agentNo", label: "代理编号", placeholder: "AG001" },
  { key: "agentName", label: "代理名称" },
  { key: "loginPhone", label: "登录手机" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "dataScope", label: "数据范围", type: "select", options: SCOPE_OPTIONS },
];

function AgentsInner() {
  const paging = usePaging();
  const onTabChange = () => { paging.reset(); setKeyword(""); setShowArchived(false); };
  const tabs = useNavTabs("/agents", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  // 代理绩效周期（复用报表域缺省值 LAST_30D）
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  const [keyword, setKeyword] = useState("");
  const [edit, setEdit] = useState<Agent | null>(null);
  const [form, setForm] = useState<Partial<Agent>>({});
  const [accountForm, setAccountForm] = useState<Partial<AgentAccount> | null>(null);
  const [commissionForm, setCommissionForm] = useState<Partial<AgentCommission> | null>(null);
  // 划拨（S1）：三个抽屉——划拨 / 回收 / 划拨流水
  const [assignForm, setAssignForm] = useState<{ agentNo: string; cabinetNos: string; siteNos: string } | null>(null);
  const [reclaimForm, setReclaimForm] = useState<{ agentNo: string; agentName: string; cabinetNos: string; siteNos: string } | null>(null);
  const [recordsFor, setRecordsFor] = useState<{ agentNo: string; agentName: string } | null>(null);
  const recPaging = usePaging();
  const username = useAuth((s) => s.username);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」只作用于代理商档案 tab（TDD §10.1），切 tab 复位
  const [showArchived, setShowArchived] = useState(false);

  const profiles = useQuery({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["agents", paging.page, paging.size, keyword, showArchived],
    queryFn: () => api.listAgents({ page: paging.page, size: paging.size, keyword, showArchived }),
    placeholderData: keepPreviousData,
    enabled: tab === "profiles",
  });
  const assign = useQuery({
    queryKey: ["agent-assign", paging.page, paging.size, keyword],
    queryFn: () => api.listAgentAssignments({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "assign",
  });
  const performance = useQuery({
    queryKey: ["agent-performance", paging.page, paging.size, keyword, period],
    queryFn: () => api.listAgentPerformance({ page: paging.page, size: paging.size, keyword, period }),
    placeholderData: keepPreviousData,
    enabled: tab === "performance",
  });
  const accounts = useQuery({
    queryKey: ["agent-accounts", paging.page, paging.size, keyword],
    queryFn: () => api.listAgentAccounts({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "accounts",
  });
  const commissions = useQuery({
    queryKey: ["agent-commissions", paging.page, paging.size, keyword],
    queryFn: () => api.listAgentCommissions({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "commission",
  });

  // —— 划拨相关查询（都只在划拨 tab / 抽屉打开时才拉）——
  const canAssign = allow("agent:scope:assign");
  // 目标代理商下拉：取在用（未归档）代理商，一次拉全量，抽屉里不再分页
  const agentOptionsQ = useQuery({
    queryKey: ["agent-options"],
    queryFn: () => api.listAgents({ page: 1, size: UNPAGED_SIZE }),
    enabled: tab === "assign",
  });
  const agentOptions = useMemo(
    () => (agentOptionsQ.data?.list ?? [])
      .filter((a) => !a.archivedAt && a.status === "ENABLED")
      .map((a) => ({ value: a.agentNo, label: `${a.agentNo} · ${a.name}（${a.regionScope}）` })),
    [agentOptionsQ.data],
  );
  // 划拨候选：只列**未归属或归属其它代理**的资产（excludeAgentNo = 目标代理）
  const assignableQ = useQuery({
    queryKey: ["assignable-assets", assignForm?.agentNo ?? ""],
    queryFn: () => api.listAssignableAssets({ page: 1, size: UNPAGED_SIZE, excludeAgentNo: assignForm?.agentNo || undefined }),
    enabled: !!assignForm,
  });
  // 回收候选：只列该代理名下的资产
  const reclaimableQ = useQuery({
    queryKey: ["reclaimable-assets", reclaimForm?.agentNo ?? ""],
    queryFn: () => api.listAssignableAssets({ page: 1, size: UNPAGED_SIZE, agentNo: reclaimForm?.agentNo }),
    enabled: !!reclaimForm?.agentNo,
  });
  const records = useQuery({
    queryKey: ["assign-records", recordsFor?.agentNo ?? "", recPaging.page, recPaging.size],
    queryFn: () => api.listAgentAssignmentRecords({ page: recPaging.page, size: recPaging.size, agentNo: recordsFor?.agentNo || undefined }),
    placeholderData: keepPreviousData,
    enabled: !!recordsFor,
  });

  const canEditAccount = allow("agent:agent:update");
  const save = useMutation({
    mutationFn: (a: Partial<Agent>) => api.saveAgent(a),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agents"] }); setEdit(null); },
  });
  const saveAccount = useMutation({
    mutationFn: (a: Partial<AgentAccount>) => api.saveAgentAccount(a),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agent-accounts"] }); notify.success("保存成功"); setAccountForm(null); },
  });
  const saveCommission = useMutation({
    mutationFn: (c: Partial<AgentCommission>) => api.saveAgentCommission(c),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agent-commissions"] }); notify.success("保存成功"); setCommissionForm(null); },
  });

  // 归档 / 恢复（G1 软删除）。错误由全局 MutationCache 接管，页面不重复 catch。
  const archiveAgent = useMutation({
    mutationFn: (no: string) => api.archiveAgent(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agents"] }); notify.success("已归档"); },
  });
  const unarchiveAgent = useMutation({
    mutationFn: (no: string) => api.unarchiveAgent(no),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agents"] }); notify.success("已恢复"); },
  });

  // —— 划拨 / 回收（S1）——
  // 落库点是资产上的 agentNo；成功后把「代理商档案（设备数）」「划拨汇总」「候选池」「流水」全部作废重拉，
  // 这样设备数/点位数当场就变——看得见的变化才证明真落库了。
  const invalidateAssets = () => {
    for (const key of [["agents"], ["agent-options"], ["agent-assign"], ["agent-performance"], ["assignable-assets"], ["reclaimable-assets"], ["assign-records"]]) {
      qc.invalidateQueries({ queryKey: key });
    }
  };
  const assignAssets = useMutation({
    mutationFn: (v: { agentNo: string; cabinetNos: string[]; siteNos: string[] }) =>
      api.assignAgentAssets({ ...v, operatorName: username || undefined }),
    onSuccess: (recs) => { invalidateAssets(); notify.success(`已划拨 ${recs.length} 项资产`); setAssignForm(null); },
  });
  const reclaimAssets = useMutation({
    mutationFn: (v: { cabinetNos: string[]; siteNos: string[] }) =>
      api.reclaimAgentAssets({ ...v, operatorName: username || undefined }),
    onSuccess: (recs) => { invalidateAssets(); notify.success(`已回收 ${recs.length} 项资产至平台直营`); setReclaimForm(null); },
  });

  const assignFields: FieldDef[] = useMemo(() => [
    {
      key: "agentNo", label: "目标代理商", type: "select", required: true,
      options: [{ value: "", label: "请选择代理商" }, ...agentOptions],
      help: "只列启用中的代理商；已停用/已归档的不能作为划拨对象",
    },
    {
      key: "cabinetNos", label: "机柜（可多选）", type: "multiselect", csv: true,
      placeholder: assignForm?.agentNo ? "选择要划拨的机柜" : "先选目标代理商",
      options: toOptions(assignableQ.data?.list, "CABINET"),
      disabledWhen: (v) => !v.agentNo,
      help: "只列未归属或归属其它代理的机柜，括号里是当前归属——别把别人的柜子划走了",
    },
    {
      key: "siteNos", label: "站点（可多选）", type: "multiselect", csv: true,
      placeholder: assignForm?.agentNo ? "选择要划拨的站点" : "先选目标代理商",
      options: toOptions(assignableQ.data?.list, "SITE"),
      disabledWhen: (v) => !v.agentNo,
      help: "站点划拨只改站点归属，站内机柜的归属需另行选择",
    },
  ], [agentOptions, assignableQ.data, assignForm?.agentNo]);

  const reclaimFields: FieldDef[] = useMemo(() => [
    {
      key: "cabinetNos", label: "回收机柜（可多选）", type: "multiselect", csv: true,
      placeholder: "选择要收回的机柜", options: toOptions(reclaimableQ.data?.list, "CABINET"),
    },
    {
      key: "siteNos", label: "回收站点（可多选）", type: "multiselect", csv: true,
      placeholder: "选择要收回的站点", options: toOptions(reclaimableQ.data?.list, "SITE"),
    },
  ], [reclaimableQ.data]);

  async function submitAssign() {
    if (!assignForm) return;
    const cabs = csvArr(assignForm.cabinetNos);
    const sts = csvArr(assignForm.siteNos);
    if (!assignForm.agentNo) { notify.error("请先选择目标代理商"); return; }
    if (!cabs.length && !sts.length) { notify.error("请至少选择一台机柜或一个站点"); return; }
    const target = agentOptionsQ.data?.list.find((a) => a.agentNo === assignForm.agentNo);
    const ok = await confirm({
      title: "确认划拨",
      desc: `将把 ${cabs.length} 台机柜、${sts.length} 个站点划拨给 ${target?.name ?? ""}（${assignForm.agentNo}）。`
        + "划拨后这些资产的归属立即变更，并记入划拨流水（可追溯操作人）。",
      confirmText: "确认划拨",
    });
    if (ok) assignAssets.mutate({ agentNo: assignForm.agentNo, cabinetNos: cabs, siteNos: sts });
  }
  async function submitReclaim() {
    if (!reclaimForm) return;
    const cabs = csvArr(reclaimForm.cabinetNos);
    const sts = csvArr(reclaimForm.siteNos);
    if (!cabs.length && !sts.length) { notify.error("请至少选择一台机柜或一个站点"); return; }
    const ok = await confirm({
      title: "确认回收",
      danger: true,
      desc: `将把 ${cabs.length} 台机柜、${sts.length} 个站点从 ${reclaimForm.agentName}（${reclaimForm.agentNo}）收回平台直营。`
        + "代理商将不再有这些资产的收益与数据权限。",
      confirmText: "确认回收",
    });
    if (ok) reclaimAssets.mutate({ cabinetNos: cabs, siteNos: sts });
  }

  function open(a: Agent) { setEdit(a); setForm(a); }

  // 业务号列一律 txt-strong（§12.3 主键列加强）；比例/计数/金额列 text-right + tabular-nums（§12.4）
  const profileCols: Column<Agent>[] = [
    { header: "代理编号", cell: (a) => <span className="txt-strong tabular-nums">{a.agentNo}</span> },
    { header: "名称", cell: (a) => a.name },
    { header: "辖域", cell: (a) => <span className="text-muted-foreground">{a.regionScope}</span> },
    { header: "联系方式", cell: (a) => <span className="text-muted-foreground">{a.contact}</span> },
    { header: "分润比例", className: "text-right", cell: (a) => <span className="tabular-nums">{(a.shareRate * 100).toFixed(0)}%</span> },
    { header: "设备数", className: "text-right", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
    { header: "状态", cell: (a) => a.status === "ENABLED" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    // 归档时间列只在「显示已归档」打开时出现，默认视图里整列都是 `-` 属于噪音
    ...(showArchived ? [{ header: "归档时间", cell: (a: Agent) => <ArchivedAt at={a.archivedAt} /> }] : []),
    {
      header: "操作",
      cell: (a) => (
        <ArchiveActions
          archived={!!a.archivedAt}
          canWrite={allow("agent:agent:update")}
          actions={<Button size="sm" variant="outline" onClick={() => open(a)}>配置</Button>}
          // 代理商是主数据：要求手输代理编号确认
          onArchive={async () => { if (await confirm(archiveConfirm("代理商", a.agentNo, a.agentNo))) archiveAgent.mutate(a.agentNo); }}
          onUnarchive={async () => { if (await confirm(unarchiveConfirm("代理商", a.agentNo))) unarchiveAgent.mutate(a.agentNo); }}
        />
      ),
    },
  ];

  const assignCols: Column<AgentAssignment>[] = [
    { header: "代理编号", cell: (a) => <span className="txt-strong tabular-nums">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "区域", cell: (a) => <span className="text-muted-foreground">{a.region}</span> },
    // 这两列由 cabinets.agentNo / sites.agentNo 实时反算：划拨/回收后数字当场变
    { header: "设备数", className: "text-right", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
    { header: "点位数", className: "text-right", cell: (a) => <span className="tabular-nums">{a.siteCount}</span> },
    {
      header: "操作",
      cell: (a) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setRecordsFor({ agentNo: a.agentNo, agentName: a.agentName })}>流水</Button>
          {canAssign && (
            <>
              <Button size="sm" variant="outline" onClick={() => setAssignForm({ agentNo: a.agentNo, cabinetNos: "", siteNos: "" })}>划拨</Button>
              <Button
                size="sm"
                variant="outline"
                disabled={a.cabinetCount + a.siteCount === 0}
                onClick={() => setReclaimForm({ agentNo: a.agentNo, agentName: a.agentName, cabinetNos: "", siteNos: "" })}
              >回收</Button>
            </>
          )}
        </div>
      ),
    },
  ];

  const recordCols: Column<AgentAssignmentRecord>[] = [
    { header: "流水号", cell: (r) => <span className="txt-strong tabular-nums">{r.assignmentNo}</span> },
    { header: "代理", cell: (r) => <span>{r.agentName} <span className="text-muted-foreground tabular-nums">{r.agentNo}</span></span> },
    { header: "资产", cell: (r) => <span className="tabular-nums">{r.assetNo}</span> },
    { header: "类型", cell: (r) => <Badge tone="outline">{ASSET_TYPE_LABEL[r.assetType]}</Badge> },
    { header: "动作", cell: (r) => r.action === "ASSIGN" ? <Badge tone="success">划拨</Badge> : <Badge tone="warning">回收</Badge> },
    { header: "操作人", cell: (r) => r.operatorName },
    { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];

  const perfCols: Column<AgentPerformance>[] = [
    { header: "排名", className: "text-right", cell: (a) => <span className="txt-strong tabular-nums">#{a.rank}</span> },
    { header: "代理编号", cell: (a) => <span className="txt-strong tabular-nums">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "GMV", className: "text-right", cell: (a) => <span className="tabular-nums">{money(a.gmv, a.currency)}</span> },
    { header: "设备数", className: "text-right", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
    { header: "在线率", className: "text-right", cell: (a) => <span className="tabular-nums">{(a.onlineRate * 100).toFixed(0)}%</span> },
  ];

  const accountCols: Column<AgentAccount>[] = [
    { header: "账号编号", cell: (a) => <span className="txt-strong tabular-nums">{a.accountNo}</span> },
    { header: "代理编号", cell: (a) => <span className="text-muted-foreground tabular-nums">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "登录手机", cell: (a) => <span className="tabular-nums">{a.loginPhone}</span> },
    { header: "状态", cell: (a) => a.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "数据范围", cell: (a) => <Badge tone="outline">{SCOPE_LABEL[a.dataScope]}</Badge> },
    { header: "创建时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    { header: "操作", cell: (a) => canEditAccount ? <Button size="sm" variant="outline" onClick={() => setAccountForm(a)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const commissionCols: Column<AgentCommission>[] = [
    { header: "规则号", cell: (c) => <span className="txt-strong tabular-nums">{c.ruleNo}</span> },
    { header: "代理编号", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.agentNo}</span> },
    { header: "代理名称", cell: (c) => c.agentName },
    { header: "计佣基数", cell: (c) => <Badge tone="outline">{c.basis === "GMV" ? "GMV" : "订单量"}</Badge> },
    { header: "分润比例", className: "text-right", cell: (c) => <span className="tabular-nums">{(c.rate * 100).toFixed(0)}%</span> },
    { header: "结算模式", cell: (c) => c.mode === "CHANNEL_SPLIT" ? "渠道分成" : "账务分录" },
    { header: "生效日期", cell: (c) => <span className="text-muted-foreground">{c.effectiveAt}</span> },
    { header: "状态", cell: (c) => c.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "操作", cell: (c) => allow("agent:settlement:read") ? <Button size="sm" variant="outline" onClick={() => setCommissionForm(c)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  // —— 导出（TDD §10.2）：当页数据，列与表格可见列严格一致 ——
  const exportProfiles = () => exportCsv<Agent>("代理商档案", [
    { header: "代理编号", value: (a) => a.agentNo },
    { header: "名称", value: (a) => a.name },
    { header: "辖域", value: (a) => a.regionScope },
    { header: "联系方式", value: (a) => a.contact },
    { header: "分润比例", value: (a) => `${(a.shareRate * 100).toFixed(0)}%` },
    { header: "设备数", value: (a) => a.cabinetCount },
    { header: "状态", value: (a) => (a.status === "ENABLED" ? "启用" : "停用") },
    ...(showArchived ? [{ header: "归档时间", value: (a: Agent) => (a.archivedAt ? fmtTime(a.archivedAt) : "") }] : []),
  ], profiles.data?.list ?? []);
  const exportCommissions = () => exportCsv<AgentCommission>("分润配置", [
    { header: "规则号", value: (c) => c.ruleNo },
    { header: "代理编号", value: (c) => c.agentNo },
    { header: "代理名称", value: (c) => c.agentName },
    { header: "计佣基数", value: (c) => (c.basis === "GMV" ? "GMV" : "订单量") },
    { header: "分润比例", value: (c) => `${(c.rate * 100).toFixed(0)}%` },
    { header: "结算模式", value: (c) => (c.mode === "CHANNEL_SPLIT" ? "渠道分成" : "账务分录") },
    { header: "生效日期", value: (c) => c.effectiveAt },
    { header: "状态", value: (c) => (c.status === "ACTIVE" ? "启用" : "停用") },
  ], commissions.data?.list ?? []);
  const exportAssign = () => exportCsv<AgentAssignment>("设备点位划拨", [
    { header: "代理编号", value: (a) => a.agentNo },
    { header: "代理名称", value: (a) => a.agentName },
    { header: "区域", value: (a) => a.region },
    { header: "设备数", value: (a) => a.cabinetCount },
    { header: "点位数", value: (a) => a.siteCount },
  ], assign.data?.list ?? []);
  const exportPerformance = () => exportCsv<AgentPerformance>(`代理绩效-${periodLabel(period)}`, [
    { header: "排名", value: (a) => a.rank },
    { header: "代理编号", value: (a) => a.agentNo },
    { header: "代理名称", value: (a) => a.agentName },
    { header: "GMV", value: (a) => money(a.gmv, a.currency) },
    { header: "设备数", value: (a) => a.cabinetCount },
    { header: "在线率", value: (a) => `${(a.onlineRate * 100).toFixed(0)}%` },
  ], performance.data?.list ?? []);
  const exportAccounts = () => exportCsv<AgentAccount>("代理账号", [
    { header: "账号编号", value: (a) => a.accountNo },
    { header: "代理编号", value: (a) => a.agentNo },
    { header: "代理名称", value: (a) => a.agentName },
    { header: "登录手机", value: (a) => a.loginPhone },
    { header: "状态", value: (a) => (a.status === "ACTIVE" ? "启用" : "停用") },
    { header: "数据范围", value: (a) => SCOPE_LABEL[a.dataScope] },
    { header: "创建时间", value: (a) => fmtTime(a.createdAt) },
  ], accounts.data?.list ?? []);
  // 无数据时不给导出按钮：导出一个空 CSV 只会让人以为功能坏了
  const exportIf = (fn: () => void, n?: number) => (n ? fn : undefined);

  const total =
    tab === "profiles" ? profiles.data?.total
    : tab === "commission" ? commissions.data?.total
    : tab === "assign" ? assign.data?.total
    : tab === "performance" ? performance.data?.total
    : accounts.data?.total;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "profiles" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索代理名称 / 编号 / 辖域"
            onExport={exportIf(exportProfiles, profiles.data?.list?.length)}
          >
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
          </Toolbar>
          <DataTable
            rowKey={(a: Agent) => a.agentNo}
            columns={profileCols}
            rows={profiles.data?.list}
            loading={profiles.isLoading} error={profiles.error} onRetry={profiles.refetch}
            rowClassName={archivedRowClass}
            empty={showArchived
              ? "没有匹配的代理商——换个关键词试试"
              : "没有在用的代理商——可能都已归档（打开「显示已归档」查看），或换个关键词试试"}
          />
        </>
      )}
      {tab === "commission" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索规则号 / 代理编号 / 名称"
            onExport={exportIf(exportCommissions, commissions.data?.list?.length)}
            onAdd={allow("agent:settlement:read") ? () => setCommissionForm({ status: "ACTIVE", basis: "GMV", mode: "CHANNEL_SPLIT", rate: 0.1 }) : undefined}
            addLabel="新增分润规则"
          />
          <DataTable rowKey={(c: AgentCommission) => c.ruleNo} columns={commissionCols} rows={commissions.data?.list} loading={commissions.isLoading} error={commissions.error} onRetry={commissions.refetch}
            empty="暂无分润规则——代理商需配置规则后才会参与分润，可点「新增分润规则」建一条" />
        </>
      )}
      {tab === "assign" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索代理编号 / 名称 / 区域"
            onExport={exportIf(exportAssign, assign.data?.list?.length)}
            onAdd={canAssign ? () => setAssignForm({ agentNo: "", cabinetNos: "", siteNos: "" }) : undefined}
            addLabel="划拨设备/点位"
          >
            <Button variant="outline" onClick={() => { setRecordsFor({ agentNo: "", agentName: "" }); recPaging.reset(); }}>全部划拨流水</Button>
          </Toolbar>
          {/* 权限降级显式提示，不静默隐藏——静默隐藏会让人以为功能坏了。
              句式与权限码的排布交给 ReadOnlyNotice，手写会各页各一套（规范 §13） */}
          {!canAssign && (
            <ReadOnlyNotice what="划拨" perm="agent:scope:assign" note="可查看归属汇总与划拨流水" />
          )}
          <DataTable rowKey={(a: AgentAssignment) => a.agentNo} columns={assignCols} rows={assign.data?.list} loading={assign.isLoading} error={assign.error} onRetry={assign.refetch}
            empty="没有匹配的代理商——划拨以代理商为单位进行，先在「代理商档案」建档，或换个关键词" />
        </>
      )}
      {tab === "performance" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索代理编号 / 名称"
            onExport={exportIf(exportPerformance, performance.data?.list?.length)}>
            <FilterSelect
              value={period}
              onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }}
              options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
              aria-label="按统计周期筛选"
            />
          </Toolbar>
          <DataTable rowKey={(a: AgentPerformance) => a.agentNo} columns={perfCols} rows={performance.data?.list} loading={performance.isLoading} error={performance.error} onRetry={performance.refetch}
            empty={`${periodLabel(period)}内没有绩效数据——统计截至昨日（T+1），代理名下站点需先产生订单；可换更长周期，或先在「设备/点位划拨」把资产划给代理`} />
        </>
      )}
      {tab === "accounts" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); paging.reset(); }}
            searchPlaceholder="搜索账号编号 / 代理 / 登录手机"
            onExport={exportIf(exportAccounts, accounts.data?.list?.length)}
            onAdd={canEditAccount ? () => setAccountForm({ status: "ACTIVE", dataScope: "AGENT" }) : undefined}
            addLabel="新增代理账号"
          />
          <DataTable rowKey={(a: AgentAccount) => a.accountNo} columns={accountCols} rows={accounts.data?.list} loading={accounts.isLoading} error={accounts.error} onRetry={accounts.refetch}
            empty="暂无代理账号——代理商需要账号才能登录代理端，可点「新增代理账号」开通" />
        </>
      )}

      {total != null && <Pagination page={paging.page} size={paging.size} total={total} onPage={paging.setPage} onSize={paging.setSize} />}

      <Drawer
        open={!!edit}
        onOpenChange={(o) => !o && setEdit(null)}
        title={`代理商 ${edit?.agentNo ?? ""}`}
        desc="档案与默认分润（分润执行/打款经 nearpay）"
        footer={
          <>
            <Button variant="outline" onClick={() => setEdit(null)}>取消</Button>
            <Button onClick={() => save.mutate(form)} disabled={save.isPending}>保存</Button>
          </>
        }
      >
        <Field label="名称"><Input value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="辖域"><Input value={form.regionScope ?? ""} onChange={(e) => setForm({ ...form, regionScope: e.target.value })} /></Field>
        <Field label="联系方式"><Input value={form.contact ?? ""} onChange={(e) => setForm({ ...form, contact: e.target.value })} /></Field>
        <Field label="默认分润比例（%）">
          <Input type="number" value={form.shareRate != null ? Math.round(form.shareRate * 100) : ""} onChange={(e) => setForm({ ...form, shareRate: Number(e.target.value) / 100 })} />
        </Field>
      </Drawer>

      <FormDrawer
        open={!!accountForm}
        onOpenChange={(o) => !o && setAccountForm(null)}
        titleNew="新增代理账号"
        titleEdit={`编辑代理账号 ${accountForm?.accountNo ?? ""}`}
        isEdit={!!accountForm?.accountNo}
        fields={ACCOUNT_FIELDS}
        value={(accountForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setAccountForm(v as Partial<AgentAccount>)}
        onSubmit={() => accountForm && saveAccount.mutate(accountForm)}
        submitting={saveAccount.isPending}
      />

      {/* 划拨抽屉：选目标代理 + 多选机柜/站点，提交前二次确认（写明 N 台机柜、M 个站点、给谁） */}
      <FormDrawer
        open={!!assignForm}
        onOpenChange={(o) => !o && setAssignForm(null)}
        titleNew="划拨设备 / 点位"
        titleEdit="划拨设备 / 点位"
        isEdit={false}
        width="w-[520px]"
        fields={assignFields}
        value={(assignForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setAssignForm(v as { agentNo: string; cabinetNos: string; siteNos: string })}
        onSubmit={submitAssign}
        submitting={assignAssets.isPending}
      />

      {/* 回收抽屉：把资产收回平台直营（agentNo 置空），候选只列该代理名下的资产 */}
      <FormDrawer
        open={!!reclaimForm}
        onOpenChange={(o) => !o && setReclaimForm(null)}
        titleNew={`回收资产 · ${reclaimForm?.agentName ?? ""}（${reclaimForm?.agentNo ?? ""}）`}
        titleEdit=""
        isEdit={false}
        width="w-[520px]"
        fields={reclaimFields}
        value={(reclaimForm ?? {}) as unknown as Record<string, unknown>}
        onChange={(v) => setReclaimForm(v as unknown as { agentNo: string; agentName: string; cabinetNos: string; siteNos: string })}
        onSubmit={submitReclaim}
        submitting={reclaimAssets.isPending}
      />

      {/* 划拨流水（审计）：谁在什么时候把哪台柜子给了谁 / 从谁那收回 */}
      <Drawer
        open={!!recordsFor}
        onOpenChange={(o) => { if (!o) { setRecordsFor(null); recPaging.reset(); } }}
        title={recordsFor?.agentNo ? `划拨流水 · ${recordsFor.agentName}（${recordsFor.agentNo}）` : "划拨流水（全部代理）"}
        desc="资产归属的每次变更都在此留痕，含操作人与时间"
        width="w-[760px]"
      >
        <DataTable
          rowKey={(r: AgentAssignmentRecord) => r.assignmentNo}
          columns={recordCols}
          rows={records.data?.list}
          loading={records.isLoading} error={records.error} onRetry={records.refetch}
          empty="暂无划拨流水——该代理名下的资产还没有过划拨或回收操作"
        />
        {records.data && <Pagination page={recPaging.page} size={recPaging.size} total={records.data.total} onPage={recPaging.setPage} onSize={recPaging.setSize} />}
      </Drawer>

      <FormDrawer
        open={!!commissionForm}
        onOpenChange={(o) => !o && setCommissionForm(null)}
        titleNew="新增分润规则"
        titleEdit={`编辑分润规则 ${commissionForm?.ruleNo ?? ""}`}
        isEdit={!!commissionForm?.ruleNo}
        fields={COMMISSION_FIELDS}
        value={(commissionForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCommissionForm(v as Partial<AgentCommission>)}
        onSubmit={() => commissionForm && saveCommission.mutate(commissionForm)}
        submitting={saveCommission.isPending}
      />

      {dialog}
    </div>
  );
}

export default function AgentsPage() {
  return <Suspense fallback={null}><AgentsInner /></Suspense>;
}
