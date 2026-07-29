"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination, EmptyState } from "@/components/ui/misc";
import { Select } from "@/components/ui/input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Employee, RoleRow, AuditEntry, DataScope, Department, StaffPerformance } from "@/lib/types";

const SIZE = 10;
const SCOPE_LABEL: Record<DataScope, string> = { ALL: "全部数据", REGION: "按区域", LOCATION: "按点位", AGENT: "按代理(自己)", SELF: "仅自己经手" };
const SCOPE_OPTIONS = (["ALL", "REGION", "LOCATION", "AGENT", "SELF"] as DataScope[]).map((s) => ({ value: s, label: SCOPE_LABEL[s] }));
const ROLE_FIELDS: FieldDef[] = [
  { key: "code", label: "角色码", placeholder: "custom_ops" },
  { key: "name", label: "名称", placeholder: "自定义运营" },
  { key: "dataScope", label: "数据范围", type: "select", options: SCOPE_OPTIONS },
  { key: "permCount", label: "权限数", type: "number" },
  { key: "memberCount", label: "成员数", type: "number" },
  { key: "builtin", label: "内置角色", type: "switch" },
];
const EMP_FIELDS: FieldDef[] = [
  { key: "employeeNo", label: "工号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "name", label: "姓名", placeholder: "Ali Hassan" },
  { key: "phone", label: "手机", placeholder: "+9715xxxxxxx" },
  { key: "email", label: "邮箱", placeholder: "ali.hassan@sharehub.ae" },
  { key: "deptName", label: "部门", placeholder: "运维" },
  { key: "roleName", label: "角色", placeholder: "运维" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "在职" }, { value: "LEFT", label: "离职" }] },
];
const DEPT_FIELDS: FieldDef[] = [
  { key: "name", label: "部门名称", placeholder: "华东运营部" },
  { key: "parent", label: "上级部门", placeholder: "（顶级留空）" },
  { key: "leader", label: "负责人", placeholder: "张三" },
  { key: "memberCount", label: "成员数", type: "number" },
];
const ALL_TABS = [
  { key: "employees", label: "员工", perm: "org:employee:read" },
  { key: "roles", label: "角色权限", perm: "org:role:read" },
  { key: "org", label: "组织架构", perm: "org:employee:read", phase: 2 as const },
  { key: "audit", label: "操作审计", perm: "org:audit:read", phase: 2 as const },
  { key: "performance", label: "绩效报表", perm: "org:employee:read", phase: 3 as const },
];

function EmployeesInner() {
  const allow = useCan();
  const qc = useQueryClient();
  const tabs = ALL_TABS.filter((t) => allow(t.perm));
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(tabs.some((t) => t.key === qTab) ? (qTab as string) : (tabs[0]?.key ?? "employees"));
  useEffect(() => { if (qTab && tabs.some((t) => t.key === qTab)) setTab(qTab); }, [qTab]);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [scopeRole, setScopeRole] = useState<RoleRow | null>(null);
  const [scope, setScope] = useState<DataScope>("ALL");
  const [roleForm, setRoleForm] = useState<Partial<RoleRow> | null>(null);
  const [deptForm, setDeptForm] = useState<Partial<Department> | null>(null);
  const [empForm, setEmpForm] = useState<Partial<Employee> | null>(null);

  const emp = useQuery({
    queryKey: ["employees", page, keyword], queryFn: () => api.listEmployees({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "employees",
  });
  const org = useQuery({
    queryKey: ["departments", page, keyword], queryFn: () => api.listDepartments({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "org",
  });
  const perf = useQuery({
    queryKey: ["staffPerformance", page, keyword], queryFn: () => api.listStaffPerformance({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "performance",
  });
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.listRoles(), enabled: tab === "roles" });
  const audit = useQuery({
    queryKey: ["audit", page, keyword], queryFn: () => api.listAudits({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "audit",
  });

  const canAssign = allow("org:role:assign");
  const canEditRole = allow("org:role:update");
  // 员工与部门同属组织维护，沿用 org:employee:update
  const canEditDept = allow("org:employee:update");
  const canEditEmp = allow("org:employee:update");
  const saveEmp = useMutation({
    mutationFn: (v: Partial<Employee>) => api.saveEmployee(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["employees"] }); notify.success("保存成功"); setEmpForm(null); },
  });
  const saveRole = useMutation({
    mutationFn: (v: Partial<RoleRow>) => api.saveRoleRow(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["roles"] }); notify.success("保存成功"); setRoleForm(null); },
  });
  const saveDept = useMutation({
    mutationFn: (v: Partial<Department>) => api.saveDepartment(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["departments"] }); notify.success("保存成功"); setDeptForm(null); },
  });

  const empCols: Column<Employee>[] = [
    { header: "工号", cell: (e) => <span className="font-medium">{e.employeeNo}</span> },
    { header: "姓名", cell: (e) => e.name },
    { header: "手机", cell: (e) => <span className="text-muted-foreground">{e.phone}</span> },
    { header: "邮箱", cell: (e) => <span className="text-muted-foreground">{e.email}</span> },
    { header: "部门", cell: (e) => <span className="text-muted-foreground">{e.deptName}</span> },
    { header: "角色", cell: (e) => <Badge tone="outline">{e.roleName}</Badge> },
    { header: "状态", cell: (e) => e.status === "ACTIVE" ? <Badge tone="success">在职</Badge> : <Badge tone="muted">离职</Badge> },
    { header: "操作", cell: (e) => canEditEmp ? <Button size="sm" variant="outline" onClick={() => setEmpForm(e)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const roleCols: Column<RoleRow>[] = [
    { header: "角色码", cell: (r) => <span className="font-medium">{r.code}</span> },
    { header: "名称", cell: (r) => r.name },
    { header: "权限数", cell: (r) => <span className="tabular-nums">{r.permCount}</span> },
    { header: "数据范围", cell: (r) => <Badge tone="outline">{SCOPE_LABEL[r.dataScope]}</Badge> },
    { header: "成员数", cell: (r) => <span className="tabular-nums">{r.memberCount}</span> },
    { header: "类型", cell: (r) => r.builtin ? <Badge tone="muted">内置</Badge> : <Badge tone="outline">自定义</Badge> },
    {
      header: "操作",
      cell: (r) => (canEditRole || canAssign) ? (
        <div className="flex gap-2">
          {canEditRole && <Button size="sm" variant="outline" onClick={() => setRoleForm(r)}>编辑</Button>}
          {canAssign && <Button size="sm" variant="outline" onClick={() => { setScopeRole(r); setScope(r.dataScope); }}>数据权限</Button>}
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];
  const orgCols: Column<Department>[] = [
    { header: "部门编号", cell: (d) => <span className="font-medium">{d.deptNo}</span> },
    { header: "部门名称", cell: (d) => d.name },
    { header: "上级部门", cell: (d) => <span className="text-muted-foreground">{d.parent || "-"}</span> },
    { header: "成员数", cell: (d) => <span className="tabular-nums">{Math.round(d.memberCount)}</span> },
    { header: "负责人", cell: (d) => <Badge tone="outline">{d.leader}</Badge> },
    { header: "操作", cell: (d) => canEditDept ? <Button size="sm" variant="outline" onClick={() => setDeptForm(d)}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];
  const perfCols: Column<StaffPerformance>[] = [
    { header: "工号", cell: (p) => <span className="font-medium">{p.employeeNo}</span> },
    { header: "姓名", cell: (p) => p.name },
    { header: "角色", cell: (p) => <Badge tone="outline">{p.role}</Badge> },
    { header: "处理量", cell: (p) => <span className="tabular-nums">{Math.round(p.handled)}</span> },
    { header: "平均解决(分钟)", cell: (p) => <span className="tabular-nums">{Math.round(p.avgResolveMins)}</span> },
    { header: "评分", cell: (p) => <Badge tone={p.score >= 90 ? "success" : p.score >= 75 ? "default" : "warning"}>{p.score.toFixed(1)}</Badge> },
  ];
  const auditCols: Column<AuditEntry>[] = [
    { header: "时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    { header: "操作人", cell: (a) => a.actor },
    { header: "动作", cell: (a) => a.action },
    { header: "对象", cell: (a) => <span className="text-muted-foreground">{a.target}</span> },
    { header: "结果", cell: (a) => a.detail },
    { header: "IP", cell: (a) => <span className="text-muted-foreground">{a.ip}</span> },
  ];

  const paged = tab === "employees" ? emp.data : tab === "org" ? org.data : tab === "performance" ? perf.data : tab === "audit" ? audit.data : undefined;
  const kw = keyword.trim().toLowerCase();
  const roleRows = (roles.data ?? []).filter((r) => !kw || `${r.code} ${r.name}`.toLowerCase().includes(kw));

  if (tabs.length === 0) return <div><PageTitle title="员工与权限" /><EmptyState title="无权限" /></div>;

  const onSearch = (v: string) => { setKeyword(v); setPage(1); };

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); }} />
      {tab === "employees" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={onSearch}
            searchPlaceholder="搜索工号 / 姓名 / 手机 / 邮箱"
            onAdd={canEditEmp ? () => setEmpForm({ name: "", phone: "", email: "", deptName: "", roleName: "", status: "ACTIVE" }) : undefined}
            addLabel="新增员工"
          />
          {!canEditEmp && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无员工维护权限（org:employee:update）</div>}
        </>
      )}
      {tab === "org" && (
        <Toolbar
          search={keyword}
          onSearch={onSearch}
          searchPlaceholder="搜索部门 / 负责人"
          onAdd={canEditDept ? () => setDeptForm({ name: "", parent: "", leader: "", memberCount: 0 }) : undefined}
          addLabel="新增部门"
        />
      )}
      {tab === "performance" && <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索工号 / 姓名" />}
      {tab === "roles" && (
        <Toolbar
          search={keyword}
          onSearch={onSearch}
          searchPlaceholder="搜索角色码 / 名称"
          onAdd={canEditRole ? () => setRoleForm({ code: "", name: "", dataScope: "ALL", permCount: 0, memberCount: 0, builtin: false }) : undefined}
          addLabel="新增角色"
        />
      )}
      {tab === "audit" && <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索操作人 / 动作 / 对象" />}
      {tab === "employees" && <DataTable rowKey={(e: Employee) => e.employeeNo} columns={empCols} rows={emp.data?.list} loading={emp.isLoading} />}
      {tab === "org" && <DataTable rowKey={(d: Department) => d.deptNo} columns={orgCols} rows={org.data?.list} loading={org.isLoading} />}
      {tab === "performance" && <DataTable rowKey={(p: StaffPerformance) => p.employeeNo} columns={perfCols} rows={perf.data?.list} loading={perf.isLoading} />}
      {tab === "roles" && <DataTable rowKey={(r: RoleRow) => r.roleNo} columns={roleCols} rows={roleRows} loading={roles.isLoading} />}
      {tab === "audit" && <DataTable rowKey={(a: AuditEntry) => a.id} columns={auditCols} rows={audit.data?.list} loading={audit.isLoading} />}
      {paged && <Pagination page={page} size={SIZE} total={paged.total} onPage={setPage} />}

      <Drawer
        open={!!scopeRole}
        onOpenChange={(o) => !o && setScopeRole(null)}
        title={`数据权限 · ${scopeRole?.name ?? ""}`}
        desc="限定该角色可见/可操作的数据范围（后端按 tenant/region/location/agent_no 拦截）"
        footer={
          <>
            <Button variant="outline" onClick={() => setScopeRole(null)}>取消</Button>
            <Button onClick={() => { qc.invalidateQueries({ queryKey: ["roles"] }); setScopeRole(null); }}>保存</Button>
          </>
        }
      >
        <Field label="功能权限">{scopeRole?.permCount} 项（角色码 {scopeRole?.code}）</Field>
        <Field label="数据范围">
          <Select className="w-full" value={scope} onChange={(e) => setScope(e.target.value as DataScope)} disabled={scopeRole?.dataScope === "AGENT"}>
            {(["ALL", "REGION", "LOCATION", "AGENT", "SELF"] as DataScope[]).map((s) => <option key={s} value={s}>{SCOPE_LABEL[s]}</option>)}
          </Select>
        </Field>
        {scope === "REGION" && <Field label="可见区域">（选择区域 · mock）Dubai North / Marina …</Field>}
        {scope === "LOCATION" && <Field label="可见点位">（选择点位 · mock）Dubai Mall / DXB T3 …</Field>}
        {scopeRole?.dataScope === "AGENT" && <div className="text-xs text-muted-foreground">代理角色数据范围强制为自己 agent_no，不可改。</div>}
      </Drawer>

      <FormDrawer
        open={!!roleForm}
        onOpenChange={(o) => !o && setRoleForm(null)}
        titleNew="新增角色"
        titleEdit={`编辑角色 ${roleForm?.code ?? ""}`}
        isEdit={!!roleForm?.roleNo}
        fields={ROLE_FIELDS}
        value={(roleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRoleForm(v as Partial<RoleRow>)}
        onSubmit={() => roleForm && saveRole.mutate(roleForm)}
        submitting={saveRole.isPending}
      />

      <FormDrawer
        open={!!empForm}
        onOpenChange={(o) => !o && setEmpForm(null)}
        titleNew="新增员工"
        titleEdit={`编辑员工 ${empForm?.employeeNo ?? ""}`}
        isEdit={!!empForm?.employeeNo}
        fields={EMP_FIELDS}
        value={(empForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setEmpForm(v as Partial<Employee>)}
        onSubmit={() => empForm && saveEmp.mutate(empForm)}
        submitting={saveEmp.isPending}
      />

      <FormDrawer
        open={!!deptForm}
        onOpenChange={(o) => !o && setDeptForm(null)}
        titleNew="新增部门"
        titleEdit={`编辑部门 ${deptForm?.deptNo ?? ""}`}
        isEdit={!!deptForm?.deptNo}
        fields={DEPT_FIELDS}
        value={(deptForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setDeptForm(v as Partial<Department>)}
        onSubmit={() => deptForm && saveDept.mutate(deptForm)}
        submitting={saveDept.isPending}
      />
    </div>
  );
}

export default function EmployeesPage() {
  return <Suspense fallback={null}><EmployeesInner /></Suspense>;
}
