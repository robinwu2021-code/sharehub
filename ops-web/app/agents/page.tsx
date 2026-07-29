"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination } from "@/components/ui/misc";
import { Input } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
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
import { notify } from "@/lib/notify";
import type { Agent, AgentAssignment, AgentPerformance, AgentAccount, AgentCommission, DataScope } from "@/lib/types";

const SIZE = 10;
// 数据范围文案与 app/employees 同源（台账 T5：AgentAccount.dataScope 原为 string
// 且 mock 里存的是中文展示文案，收紧为 DataScope 枚举后统一走映射渲染）
const SCOPE_LABEL: Record<DataScope, string> = { ALL: "全部数据", REGION: "按区域", LOCATION: "按点位", AGENT: "按代理(自己)", SELF: "仅自己经手" };
const SCOPE_OPTIONS = (["ALL", "REGION", "LOCATION", "AGENT", "SELF"] as DataScope[]).map((s) => ({ value: s, label: SCOPE_LABEL[s] }));
const TABS = [
  { key: "profiles", label: "代理商档案" },
  { key: "commission", label: "分润配置" },
  { key: "assign", label: "设备/点位划拨" },
  { key: "performance", label: "代理绩效", phase: 2 as const },
  { key: "accounts", label: "代理账号" },
];
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
const ACCOUNT_FIELDS: FieldDef[] = [
  { key: "accountNo", label: "账号编号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "agentNo", label: "代理编号", placeholder: "AG001" },
  { key: "agentName", label: "代理名称" },
  { key: "loginPhone", label: "登录手机" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "dataScope", label: "数据范围", type: "select", options: SCOPE_OPTIONS },
];

function AgentsInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "profiles");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [edit, setEdit] = useState<Agent | null>(null);
  const [form, setForm] = useState<Partial<Agent>>({});
  const [accountForm, setAccountForm] = useState<Partial<AgentAccount> | null>(null);
  const [commissionForm, setCommissionForm] = useState<Partial<AgentCommission> | null>(null);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」只作用于代理商档案 tab（TDD §10.1），切 tab 复位
  const [showArchived, setShowArchived] = useState(false);
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);

  const profiles = useQuery({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["agents", page, keyword, showArchived],
    queryFn: () => api.listAgents({ page, size: SIZE, keyword, showArchived }),
    placeholderData: keepPreviousData,
    enabled: tab === "profiles",
  });
  const assign = useQuery({
    queryKey: ["agent-assign", page, keyword],
    queryFn: () => api.listAgentAssignments({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "assign",
  });
  const performance = useQuery({
    queryKey: ["agent-performance", page, keyword],
    queryFn: () => api.listAgentPerformance({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "performance",
  });
  const accounts = useQuery({
    queryKey: ["agent-accounts", page, keyword],
    queryFn: () => api.listAgentAccounts({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "accounts",
  });
  const commissions = useQuery({
    queryKey: ["agent-commissions", page, keyword],
    queryFn: () => api.listAgentCommissions({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: tab === "commission",
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

  function open(a: Agent) { setEdit(a); setForm(a); }

  const profileCols: Column<Agent>[] = [
    { header: "代理编号", cell: (a) => <span className="font-medium">{a.agentNo}</span> },
    { header: "名称", cell: (a) => a.name },
    { header: "辖域", cell: (a) => <span className="text-muted-foreground">{a.regionScope}</span> },
    { header: "联系方式", cell: (a) => <span className="text-muted-foreground">{a.contact}</span> },
    { header: "分润比例", cell: (a) => `${(a.shareRate * 100).toFixed(0)}%` },
    { header: "设备数", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
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
    { header: "代理编号", cell: (a) => <span className="font-medium">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "区域", cell: (a) => <span className="text-muted-foreground">{a.region}</span> },
    { header: "设备数", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
    { header: "点位数", cell: (a) => <span className="tabular-nums">{a.siteCount}</span> },
  ];

  const perfCols: Column<AgentPerformance>[] = [
    { header: "排名", cell: (a) => <span className="tabular-nums font-medium">#{a.rank}</span> },
    { header: "代理编号", cell: (a) => <span className="font-medium">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "GMV", cell: (a) => <span className="tabular-nums">{money(a.gmv, a.currency)}</span> },
    { header: "设备数", cell: (a) => <span className="tabular-nums">{a.cabinetCount}</span> },
    { header: "在线率", cell: (a) => `${(a.onlineRate * 100).toFixed(0)}%` },
  ];

  const accountCols: Column<AgentAccount>[] = [
    { header: "账号编号", cell: (a) => <span className="font-medium">{a.accountNo}</span> },
    { header: "代理编号", cell: (a) => <span className="text-muted-foreground">{a.agentNo}</span> },
    { header: "代理名称", cell: (a) => a.agentName },
    { header: "登录手机", cell: (a) => <span className="tabular-nums">{a.loginPhone}</span> },
    { header: "状态", cell: (a) => a.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    { header: "数据范围", cell: (a) => <Badge tone="outline">{SCOPE_LABEL[a.dataScope]}</Badge> },
    { header: "创建时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    { header: "操作", cell: (a) => canEditAccount ? <Button size="sm" variant="outline" onClick={() => setAccountForm(a)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const commissionCols: Column<AgentCommission>[] = [
    { header: "规则号", cell: (c) => <span className="font-medium">{c.ruleNo}</span> },
    { header: "代理编号", cell: (c) => <span className="text-muted-foreground">{c.agentNo}</span> },
    { header: "代理名称", cell: (c) => c.agentName },
    { header: "计佣基数", cell: (c) => <Badge tone="outline">{c.basis === "GMV" ? "GMV" : "订单量"}</Badge> },
    { header: "分润比例", cell: (c) => `${(c.rate * 100).toFixed(0)}%` },
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
  const exportPerformance = () => exportCsv<AgentPerformance>("代理绩效", [
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
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setShowArchived(false); }} />

      {tab === "profiles" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索代理名称 / 编号 / 辖域"
            onExport={exportIf(exportProfiles, profiles.data?.list?.length)}
          >
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
          </Toolbar>
          <DataTable
            rowKey={(a: Agent) => a.agentNo}
            columns={profileCols}
            rows={profiles.data?.list}
            loading={profiles.isLoading}
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
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索规则号 / 代理编号 / 名称"
            onExport={exportIf(exportCommissions, commissions.data?.list?.length)}
            onAdd={allow("agent:settlement:read") ? () => setCommissionForm({ status: "ACTIVE", basis: "GMV", mode: "CHANNEL_SPLIT", rate: 0.1 }) : undefined}
            addLabel="新增分润规则"
          />
          <DataTable rowKey={(c: AgentCommission) => c.ruleNo} columns={commissionCols} rows={commissions.data?.list} loading={commissions.isLoading}
            empty="暂无分润规则——代理商需配置规则后才会参与分润，可点「新增分润规则」建一条" />
        </>
      )}
      {tab === "assign" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代理编号 / 名称 / 区域"
            onExport={exportIf(exportAssign, assign.data?.list?.length)} />
          <DataTable rowKey={(a: AgentAssignment) => a.agentNo} columns={assignCols} rows={assign.data?.list} loading={assign.isLoading}
            empty="暂无划拨记录——设备与点位划拨给代理后在此汇总，先在设备台账设置归属代理" />
        </>
      )}
      {tab === "performance" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代理编号 / 名称"
            onExport={exportIf(exportPerformance, performance.data?.list?.length)} />
          <DataTable rowKey={(a: AgentPerformance) => a.agentNo} columns={perfCols} rows={performance.data?.list} loading={performance.isLoading}
            empty="暂无绩效数据——代理名下设备需先产生订单，次日汇总后才会出现在此" />
        </>
      )}
      {tab === "accounts" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索账号编号 / 代理 / 登录手机"
            onExport={exportIf(exportAccounts, accounts.data?.list?.length)}
            onAdd={canEditAccount ? () => setAccountForm({ status: "ACTIVE", dataScope: "AGENT" }) : undefined}
            addLabel="新增代理账号"
          />
          <DataTable rowKey={(a: AgentAccount) => a.accountNo} columns={accountCols} rows={accounts.data?.list} loading={accounts.isLoading}
            empty="暂无代理账号——代理商需要账号才能登录代理端，可点「新增代理账号」开通" />
        </>
      )}

      {total != null && <Pagination page={page} size={SIZE} total={total} onPage={setPage} />}

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
