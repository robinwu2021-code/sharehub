"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { PageTitle, Pagination, EmptyState } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import type { Employee, RoleRow, AuditEntry, DataScope, Department, StaffPerformance } from "@/lib/types";

const SIZE = 10;
const SCOPE_LABEL: Record<DataScope, string> = { ALL: "全部数据", REGION: "按区域", LOCATION: "按点位", AGENT: "按代理(自己)", SELF: "仅自己经手" };
const SCOPE_OPTIONS = (["ALL", "REGION", "LOCATION", "AGENT", "SELF"] as DataScope[]).map((s) => ({ value: s, label: SCOPE_LABEL[s] }));
// 数据权限抽屉的表单形状：三档范围值各占一个 key（共用一个 key 会被 disabledWhen 的清空逻辑互相抹掉），
// 提交时按 dataScope 收敛成单个 scopeValues（逗号分隔 ID）。
type ScopeForm = { dataScope: DataScope; regionValues: string; locationValues: string; agentValues: string };
const EMPTY_SCOPE_FORM: ScopeForm = { dataScope: "ALL", regionValues: "", locationValues: "", agentValues: "" };
const scopeFormOf = (r: RoleRow): ScopeForm => ({
  ...EMPTY_SCOPE_FORM,
  dataScope: r.dataScope,
  regionValues: r.dataScope === "REGION" ? (r.scopeValues ?? "") : "",
  locationValues: r.dataScope === "LOCATION" ? (r.scopeValues ?? "") : "",
  agentValues: r.dataScope === "AGENT" ? (r.scopeValues ?? "") : "",
});
const scopeValuesOf = (f: ScopeForm): string =>
  f.dataScope === "REGION" ? f.regionValues
  : f.dataScope === "LOCATION" ? f.locationValues
  : f.dataScope === "AGENT" ? f.agentValues
  : ""; // ALL / SELF 无附加范围值
const csvCount = (v?: string) => (v ?? "").split(",").filter((s) => s.trim()).length;
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
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位。
  const [showArchived, setShowArchived] = useState(false);
  useEffect(() => { if (qTab && tabs.some((t) => t.key === qTab)) { setTab(qTab); setShowArchived(false); } }, [qTab]);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [scopeRole, setScopeRole] = useState<RoleRow | null>(null);
  const [scopeForm, setScopeForm] = useState<ScopeForm>(EMPTY_SCOPE_FORM);
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
  // showArchived 必须进 queryKey，否则切开关不重新拉数据
  const roles = useQuery({ queryKey: ["roles", showArchived], queryFn: () => api.listRoles({ showArchived }), enabled: tab === "roles" });
  const audit = useQuery({
    queryKey: ["audit", page, keyword], queryFn: () => api.listAudits({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "audit",
  });

  // 数据权限范围值的候选主数据（只在抽屉打开时拉，避免进页面就多三个请求）
  const scopeOpen = !!scopeRole;
  const regionsQ = useQuery({ queryKey: ["scope-regions"], queryFn: () => api.listRegions({ page: 1, size: 200 }), enabled: scopeOpen });
  const sitesQ = useQuery({ queryKey: ["scope-sites"], queryFn: () => api.listSites({ page: 1, size: 200 }), enabled: scopeOpen });
  const agentsQ = useQuery({ queryKey: ["scope-agents"], queryFn: () => api.listAgents({ page: 1, size: 200 }), enabled: scopeOpen });
  // ⚠️ AGENT 角色自身的数据范围是**锁死**的：功能权限清单 §二 明确「AGENT 数据范围强制 = 自己 agent_no」。
  // 若放开让它选任意代理，就等于代理商能看别家代理的数据——真的越权口子。
  // 其它角色（如 BD 管几家代理）选特定代理是合理的，故只针对 AGENT 这一行锁。
  const isAgentRole = scopeRole?.code === "AGENT";
  const scopeFields: FieldDef[] = useMemo(() => [
    {
      key: "dataScope", label: "数据范围", type: "select", options: SCOPE_OPTIONS,
      disabledWhen: () => isAgentRole,
      help: isAgentRole
        ? "代理商角色的数据范围强制为「自己 agent_no」，不可更改（功能权限清单 §二）"
        : "后端按 tenant/region/location/agent_no 拦截（iam_data_scope）",
    },
    {
      key: "regionValues", label: "可见区域", type: "multiselect", csv: true,
      options: (regionsQ.data?.list ?? []).map((r) => ({ value: r.regionId, label: `${r.name}（${r.regionId}）` })),
      placeholder: "选择区域（可多选）", disabledWhen: (v) => v.dataScope !== "REGION",
      help: "仅「按区域」可选；留空 = 未限定任何区域（后端视为无可见数据）",
    },
    {
      key: "locationValues", label: "可见站点", type: "multiselect", csv: true,
      options: (sitesQ.data?.list ?? []).map((s) => ({ value: s.siteNo, label: `${s.name}（${s.siteNo}）` })),
      placeholder: "选择站点（可多选）", disabledWhen: (v) => v.dataScope !== "LOCATION",
      help: "仅「按点位」可选；站点下的点位/柜机随站点一起可见",
    },
    {
      key: "agentValues", label: "可见代理商", type: "multiselect", csv: true,
      options: (agentsQ.data?.list ?? []).map((a) => ({ value: a.agentNo, label: `${a.name}（${a.agentNo}）` })),
      placeholder: "选择代理商（可多选）", disabledWhen: (v) => v.dataScope !== "AGENT" || isAgentRole,
      help: isAgentRole
        ? "代理商角色恒为「仅本人所属 agent_no」，不可指定其它代理"
        : "仅「按代理」可选；留空 = 仅本人所属 agent_no",
    },
  ], [regionsQ.data, sitesQ.data, agentsQ.data, isAgentRole]);

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
  // G7：数据权限真落库（此前 onSave 只 invalidate、选中值被丢弃，重开抽屉又变回原值）
  const saveScope = useMutation({
    mutationFn: (v: { code: string; form: ScopeForm }) => api.saveRoleDataScope(v.code, v.form.dataScope, scopeValuesOf(v.form)),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["roles"] }); notify.success("数据权限已保存"); setScopeRole(null); },
  });
  const saveDept = useMutation({
    mutationFn: (v: Partial<Department>) => api.saveDepartment(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["departments"] }); notify.success("保存成功"); setDeptForm(null); },
  });
  // 角色归档 / 恢复：错误由全局 MutationCache 接管，这里只管成功后的失效与提示。
  const archiveRoleM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveRole(v.no) : api.archiveRole(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["roles"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  // 角色是主数据（§10.1）：归档必须手输角色编号确认，避免误点把一整批人的权限来源归档掉。
  const askArchiveRole = async (r: RoleRow) => {
    if (await confirm(archiveConfirm("角色", `${r.name}（${r.roleNo}）`, r.roleNo))) archiveRoleM.mutate({ no: r.roleNo, undo: false });
  };
  const askUnarchiveRole = async (r: RoleRow) => {
    if (await confirm(unarchiveConfirm("角色", `${r.name}（${r.roleNo}）`))) archiveRoleM.mutate({ no: r.roleNo, undo: true });
  };

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
    {
      header: "数据范围",
      cell: (r) => {
        const n = csvCount(r.scopeValues);
        const needValues = r.dataScope === "REGION" || r.dataScope === "LOCATION" || r.dataScope === "AGENT";
        return (
          <div className="flex items-center gap-1.5">
            <Badge tone="outline">{SCOPE_LABEL[r.dataScope]}</Badge>
            {needValues && (
              n > 0
                ? <span className="text-xs text-muted-foreground tabular-nums">· {n} 个</span>
                : <span className="text-xs text-warning">· 未配置范围</span>
            )}
          </div>
        );
      },
    },
    { header: "成员数", cell: (r) => <span className="tabular-nums">{r.memberCount}</span> },
    { header: "类型", cell: (r) => r.builtin ? <Badge tone="muted">内置</Badge> : <Badge tone="outline">自定义</Badge> },
    // 归档时间列只在「显示已归档」打开时插入（默认视图里整列都是 "-"），且固定在操作列之前。
    ...(showArchived ? [{ header: "归档时间", cell: (r: RoleRow) => <ArchivedAt at={r.archivedAt} /> }] : []),
    {
      header: "操作",
      cell: (r) => {
        const base = (
          <>
            {canEditRole && <Button size="sm" variant="outline" onClick={() => setRoleForm(r)}>编辑</Button>}
            {/* 无 org:role:update 时按钮显式禁用（不静默隐藏），范围与数量在「数据范围」列仍可查看 */}
            <Button
              size="sm"
              variant="outline"
              disabled={!canEditRole}
              title={canEditRole ? undefined : "仅可查看：缺少 org:role:update"}
              onClick={() => { setScopeRole(r); setScopeForm(scopeFormOf(r)); }}
            >
              数据权限
            </Button>
          </>
        );
        // 已归档行一律只出「恢复」（§10.1），其余按钮不渲染。
        if (r.archivedAt) {
          return (
            <ArchiveActions
              archived
              canWrite={canEditRole}
              onArchive={() => askArchiveRole(r)}
              onUnarchive={() => askUnarchiveRole(r)}
            />
          );
        }
        // 内置角色服务端拒绝归档，这里给禁用态 + 悬浮说明（而不是把按钮藏掉——
        // 藏掉会让人以为"这行没有归档功能"，禁用+提示才说清"不是不能，是不允许"）。
        return (
          <ArchiveActions
            archived={false}
            canWrite={canEditRole}
            canArchive={!r.builtin}
            archiveHint="内置角色不可归档"
            onArchive={() => askArchiveRole(r)}
            onUnarchive={() => askUnarchiveRole(r)}
            actions={base}
          />
        );
      },
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
  // 导出当页数据（§10.2），列与表格可见列一致。
  const onExportOf = <T,>(name: string, cols: CsvColumn<T>[], rows: T[]) => () => exportCsv<T>(name, cols, rows);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setShowArchived(false); }} />
      {tab === "employees" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={onSearch}
            searchPlaceholder="搜索工号 / 姓名 / 手机 / 邮箱"
            onAdd={canEditEmp ? () => setEmpForm({ name: "", phone: "", email: "", deptName: "", roleName: "", status: "ACTIVE" }) : undefined}
            addLabel="新增员工"
            onExport={onExportOf<Employee>("员工", [
              { header: "工号", value: (e) => e.employeeNo },
              { header: "姓名", value: (e) => e.name },
              { header: "手机", value: (e) => e.phone },
              { header: "邮箱", value: (e) => e.email },
              { header: "部门", value: (e) => e.deptName },
              { header: "角色", value: (e) => e.roleName },
              { header: "状态", value: (e) => (e.status === "ACTIVE" ? "在职" : "离职") },
            ], emp.data?.list ?? [])}
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
          onExport={onExportOf<Department>("组织架构", [
            { header: "部门编号", value: (d) => d.deptNo },
            { header: "部门名称", value: (d) => d.name },
            { header: "上级部门", value: (d) => d.parent || "-" },
            { header: "成员数", value: (d) => Math.round(d.memberCount) },
            { header: "负责人", value: (d) => d.leader },
          ], org.data?.list ?? [])}
        />
      )}
      {tab === "performance" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索工号 / 姓名"
          onExport={onExportOf<StaffPerformance>("绩效报表", [
            { header: "工号", value: (p) => p.employeeNo },
            { header: "姓名", value: (p) => p.name },
            { header: "角色", value: (p) => p.role },
            { header: "处理量", value: (p) => Math.round(p.handled) },
            { header: "平均解决(分钟)", value: (p) => Math.round(p.avgResolveMins) },
            { header: "评分", value: (p) => p.score.toFixed(1) },
          ], perf.data?.list ?? [])} />
      )}
      {tab === "roles" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={onSearch}
            searchPlaceholder="搜索角色码 / 名称"
            onAdd={canEditRole ? () => setRoleForm({ code: "", name: "", dataScope: "ALL", scopeValues: "", permCount: 0, memberCount: 0, builtin: false }) : undefined}
            addLabel="新增角色"
            onExport={onExportOf<RoleRow>("角色", [
              { header: "角色码", value: (r) => r.code },
              { header: "名称", value: (r) => r.name },
              { header: "权限数", value: (r) => r.permCount },
              { header: "数据范围", value: (r) => `${SCOPE_LABEL[r.dataScope]}${csvCount(r.scopeValues) > 0 ? ` · ${csvCount(r.scopeValues)} 个` : ""}` },
              { header: "成员数", value: (r) => r.memberCount },
              { header: "类型", value: (r) => (r.builtin ? "内置" : "自定义") },
              ...(showArchived ? [{ header: "归档时间", value: (r: RoleRow) => r.archivedAt ? fmtTime(r.archivedAt) : "-" }] : []),
            ], roleRows)}
          >
            <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
          </Toolbar>
          {!canEditRole && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无角色维护权限（org:role:update），不能修改角色与数据权限</div>}
        </>
      )}
      {tab === "audit" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索操作人 / 动作 / 对象"
          onExport={onExportOf<AuditEntry>("操作审计", [
            { header: "时间", value: (a) => fmtTime(a.createdAt) },
            { header: "操作人", value: (a) => a.actor },
            { header: "动作", value: (a) => a.action },
            { header: "对象", value: (a) => a.target },
            { header: "结果", value: (a) => a.detail },
            { header: "IP", value: (a) => a.ip },
          ], audit.data?.list ?? [])} />
      )}
      {tab === "employees" && <DataTable rowKey={(e: Employee) => e.employeeNo} columns={empCols} rows={emp.data?.list} loading={emp.isLoading} empty="暂无员工——换个关键词，或点「新增员工」把运维 / 客服人员录进来。" />}
      {tab === "org" && <DataTable rowKey={(d: Department) => d.deptNo} columns={orgCols} rows={org.data?.list} loading={org.isLoading} empty="暂无部门——点「新增部门」先建顶级部门，再逐级挂下级。" />}
      {tab === "performance" && <DataTable rowKey={(p: StaffPerformance) => p.employeeNo} columns={perfCols} rows={perf.data?.list} loading={perf.isLoading} empty="暂无绩效数据——绩效按工单处理量与解决时长自动汇总，需先有已完成的工单。" />}
      {tab === "roles" && <DataTable rowKey={(r: RoleRow) => r.roleNo} columns={roleCols} rows={roleRows} loading={roles.isLoading} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的角色——换个关键词，或点「新增角色」建一个自定义角色。" : "暂无在用角色——可能都已归档（打开「显示已归档」查看），或点「新增角色」建第一个自定义角色。"} />}
      {tab === "audit" && <DataTable rowKey={(a: AuditEntry) => a.id} columns={auditCols} rows={audit.data?.list} loading={audit.isLoading} empty="暂无审计记录——记录在管理员执行写操作后自动产生，换个关键词或时间范围再看。" />}
      {paged && <Pagination page={page} size={SIZE} total={paged.total} onPage={setPage} />}

      <FormDrawer
        open={!!scopeRole}
        onOpenChange={(o) => !o && setScopeRole(null)}
        titleNew="数据权限"
        titleEdit={`数据权限 · ${scopeRole?.name ?? ""}（${scopeRole?.code ?? ""} · ${scopeRole?.permCount ?? 0} 项功能权限）`}
        isEdit
        fields={scopeFields}
        value={scopeForm as unknown as Record<string, unknown>}
        onChange={(v) => setScopeForm(v as unknown as ScopeForm)}
        onSubmit={() => scopeRole && saveScope.mutate({ code: scopeRole.code, form: scopeForm })}
        submitting={saveScope.isPending}
      />

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

      {dialog}
    </div>
  );
}

export default function EmployeesPage() {
  return <Suspense fallback={null}><EmployeesInner /></Suspense>;
}
