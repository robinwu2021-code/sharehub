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
import { money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Agent, AgentAssignment, AgentPerformance, AgentAccount } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "profiles", label: "代理商档案" },
  { key: "assign", label: "设备/点位划拨" },
  { key: "performance", label: "代理绩效", phase: 2 as const },
  { key: "accounts", label: "代理账号" },
];
const ACCOUNT_FIELDS: FieldDef[] = [
  { key: "accountNo", label: "账号编号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "agentNo", label: "代理编号", placeholder: "AGT0001" },
  { key: "agentName", label: "代理名称" },
  { key: "loginPhone", label: "登录手机" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "DISABLED", label: "停用" }] },
  { key: "dataScope", label: "数据范围", placeholder: "如：本代理 / 全辖域" },
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
  const qc = useQueryClient();
  const allow = useCan();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const profiles = useQuery({
    queryKey: ["agents", page, keyword],
    queryFn: () => api.listAgents({ page, size: SIZE, keyword }),
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

  const canEditAccount = allow("agent:agent:update");
  const save = useMutation({
    mutationFn: (a: Partial<Agent>) => api.saveAgent(a),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agents"] }); setEdit(null); },
  });
  const saveAccount = useMutation({
    mutationFn: (a: Partial<AgentAccount>) => api.saveAgentAccount(a),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["agent-accounts"] }); notify.success("保存成功"); setAccountForm(null); },
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
    { header: "操作", cell: (a) => allow("agent:agent:update") ? <Button size="sm" variant="outline" onClick={() => open(a)}>配置</Button> : <span className="text-muted-foreground">-</span> },
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
    { header: "数据范围", cell: (a) => <Badge tone="outline">{a.dataScope}</Badge> },
    { header: "创建时间", cell: (a) => <span className="text-muted-foreground">{a.createdAt}</span> },
    { header: "操作", cell: (a) => canEditAccount ? <Button size="sm" variant="outline" onClick={() => setAccountForm(a)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  const total =
    tab === "profiles" ? profiles.data?.total
    : tab === "assign" ? assign.data?.total
    : tab === "performance" ? performance.data?.total
    : accounts.data?.total;

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); }} />

      {tab === "profiles" && (
        <>
          <div className="mb-4"><Input className="w-64" placeholder="搜索代理名称 / 编号 / 辖域" value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(1); }} /></div>
          <DataTable rowKey={(a: Agent) => a.agentNo} columns={profileCols} rows={profiles.data?.list} loading={profiles.isLoading} />
        </>
      )}
      {tab === "assign" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代理编号 / 名称 / 区域" />
          <DataTable rowKey={(a: AgentAssignment) => a.agentNo} columns={assignCols} rows={assign.data?.list} loading={assign.isLoading} />
        </>
      )}
      {tab === "performance" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代理编号 / 名称" />
          <DataTable rowKey={(a: AgentPerformance) => a.agentNo} columns={perfCols} rows={performance.data?.list} loading={performance.isLoading} />
        </>
      )}
      {tab === "accounts" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder="搜索账号编号 / 代理 / 登录手机"
            onAdd={canEditAccount ? () => setAccountForm({ status: "ACTIVE", dataScope: "本代理" }) : undefined}
            addLabel="新增代理账号"
          />
          <DataTable rowKey={(a: AgentAccount) => a.accountNo} columns={accountCols} rows={accounts.data?.list} loading={accounts.isLoading} />
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
    </div>
  );
}

export default function AgentsPage() {
  return <Suspense fallback={null}><AgentsInner /></Suspense>;
}
