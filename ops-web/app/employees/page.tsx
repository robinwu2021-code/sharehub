"use client";

import { Suspense, useEffect, useMemo, useState, type ReactNode } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { PageTitle, Pagination, EmptyState } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab } from "@/lib/hooks/use-page-tab";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { Tree, type TreeNode } from "@/components/ui/tree";
import { Notice } from "@/components/ui/notice";
import { Card } from "@/components/ui/card";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Input } from "@/components/ui/input";
import { Table, THead, TBody, TR, TH, TD } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
// 绩效周期复用报表域枚举，与站点坪效/代理绩效同一套口径
import { REPORT_PERIODS, REPORT_PERIOD_DEFAULT, type ReportPeriod } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import type {
  Employee, RoleRow, AuditEntry, AuditOutcome, AuditClient, DataScope, Department, StaffPerformance, PermissionItem,
} from "@/lib/types";

// 组织架构与权限目录都要整棵拉（树不能分页——少半棵树等于错的树），单独给个大 size。
const TREE_SIZE = 500;
// 「按站点」而不是「按点位」：后端实现的档位是 SITE（登记在 loc_site / loc_location /
// dev_cabinet / ord_order / wo_order 五张表），选中的也是站点。
// 此前这里是 LOCATION —— 一个后端一张表都没登记的档位，选了就什么都看不见（见 DataScope 注释）。
const SCOPE_LABEL: Record<DataScope, string> = { ALL: "全部数据", REGION: "按区域", SITE: "按站点", AGENT: "按代理(自己)", SELF: "仅自己经手" };
const SCOPE_OPTIONS = (["ALL", "REGION", "SITE", "AGENT", "SELF"] as DataScope[]).map((s) => ({ value: s, label: SCOPE_LABEL[s] }));
// 数据权限抽屉的表单形状：三档范围值各占一个 key（共用一个 key 会被 disabledWhen 的清空逻辑互相抹掉），
// 提交时按 dataScope 收敛成单个 scopeRefs（逗号分隔 ID）。
type ScopeForm = { dataScope: DataScope; regionValues: string; siteValues: string; agentValues: string };
const EMPTY_SCOPE_FORM: ScopeForm = { dataScope: "ALL", regionValues: "", siteValues: "", agentValues: "" };
const scopeFormOf = (r: RoleRow): ScopeForm => ({
  ...EMPTY_SCOPE_FORM,
  dataScope: r.dataScope,
  regionValues: r.dataScope === "REGION" ? (r.scopeRefs ?? "") : "",
  siteValues: r.dataScope === "SITE" ? (r.scopeRefs ?? "") : "",
  agentValues: r.dataScope === "AGENT" ? (r.scopeRefs ?? "") : "",
});
const scopeValuesOf = (f: ScopeForm): string =>
  f.dataScope === "REGION" ? f.regionValues
  : f.dataScope === "SITE" ? f.siteValues
  : f.dataScope === "AGENT" ? f.agentValues
  : ""; // ALL / SELF 无附加范围值
const csvCount = (v?: string) => (v ?? "").split(",").filter((s) => s.trim()).length;
const ROLE_FIELDS: FieldDef[] = [
  { key: "code", label: "角色码", placeholder: "custom_ops" },
  { key: "name", label: "名称", placeholder: "自定义运营" },
  { key: "dataScope", label: "数据范围", type: "select", options: SCOPE_OPTIONS },
  // 权限数不在这里填：它是「功能权限勾选树里勾了几项」的派生量，手填只会和实际授权对不上。
  { key: "memberCount", label: "成员数", type: "number" },
  { key: "builtin", label: "内置角色", type: "switch" },
];
/**
 * 员工表单。**角色必须选编号，不能填名字** ——
 * 此前这里是一个自由文本框（key 为 `roleName`），而后端认的是 `roleNo`：
 * 填进去的名字不是任何一列，**被静默丢弃**，那个人的角色一直是空的，
 * 于是他登录后什么菜单都没有，而表单上明明写着「运维」。
 */
function empFields(roles: RoleRow[]): FieldDef[] {
  const opts = roles.filter((r) => !r.archivedAt)
    .map((r) => ({ value: r.roleNo, label: `${r.name}（${r.code}）` }));
  return [
    { key: "employeeNo", label: "工号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
    { key: "name", label: "姓名", placeholder: "Ali Hassan" },
    { key: "phone", label: "手机", placeholder: "+9715xxxxxxx" },
    { key: "email", label: "邮箱", placeholder: "ali.hassan@sharehub.ae" },
    { key: "deptName", label: "部门", placeholder: "运维" },
    {
      key: "roleNo", label: "主角色", type: "select", options: opts, required: true,
      help: "列表显示的就是它；权限取下面「全部角色」的并集，主角色总在其中",
    },
    {
      key: "roleNos", label: "全部角色", type: "multiselect", options: opts,
      placeholder: "只有主角色时可以不选",
      help: "会话权限 = 这些角色的并集。主角色会自动并进来，不必重复勾",
    },
    { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "在职" }, { value: "LEFT", label: "离职" }] },
  ];
}
// 绩效评分档位。原先是内联 `Badge tone={score>=90?…}`：颜色成了「好/差」的唯一线索（§11.4），
// 且阈值口径写在渲染处。拆成「数值列 + 档位徽标」——数值给精度，档位给结论。
/** 周期码 → 中文标签。取自 REPORT_PERIODS，不另抄一份。 */
const periodLabel = (p: string) => REPORT_PERIODS.find((x) => x.value === p)?.label ?? p;

const PERF_GRADE: StatusMap<"EXCELLENT" | "GOOD" | "WATCH"> = {
  EXCELLENT: { label: "优秀", tone: "success" },
  GOOD: { label: "达标", tone: "default" },
  WATCH: { label: "待改进", tone: "warning" },
};
/**
 * 审计结果。**失败的也记**，所以界面必须分得出三种 ——
 * 此前无论成败都渲染成绿色的「成功」徽标，一条被拒绝的操作看上去和成功的一模一样。
 */
const AUDIT_OUTCOME: StatusMap<AuditOutcome> = {
  SUCCESS: { label: "成功", tone: "success" },
  // 用 danger 不用 warning：这是「有人试图做他没权限做的事」，是安全信号不是小毛病
  DENIED: { label: "被拒绝", tone: "danger" },
  FAILED: { label: "未完成", tone: "warning" },
};

/** 从哪个端做的。结算争议里第一个被问到的就是「这是运营改的还是代理自己改的」。 */
const AUDIT_CLIENT: StatusMap<AuditClient> = {
  OPS: { label: "运营台", tone: "default" },
  AGENT: { label: "代理端", tone: "info" },
  MP: { label: "C 端", tone: "info" },
};

const gradeOf = (score: number): keyof typeof PERF_GRADE =>
  score >= 90 ? "EXCELLENT" : score >= 75 ? "GOOD" : "WATCH";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 本页原先自己判权（这点是对的，多数页面连这个都没有），只是名字与权限各存一份。
const TAB_KEYS = ["employees", "roles", "org", "audit", "performance"] as const;

// —— 组织架构树 ——
/**
 * 按 `parent`（上级 deptNo）拼树。挂不上父节点的（parent 指向不存在的部门）**提到顶层**，
 * 不是丢掉：mock 自洽性由 org-tree.test.ts 兜，但真实后端一旦回来一条脏数据，
 * 静默吞掉整棵子树是最难查的那种 bug，宁可让它显眼地漂在顶层。
 */
/**
 * 同级按 sort 升序；sort 相同再按 deptNo 兜底，**保证顺序稳定**。
 * 此前完全不排序 —— 同级顺序由接口返回顺序决定，后台配好的次序不生效，
 * 换个查询条件还可能变，而这种"顺序不对"在页面上很难被认出是 bug。
 */
const bySort = (ds: Department[]) =>
  [...ds].sort((a, b) => a.sort - b.sort || a.deptNo.localeCompare(b.deptNo));

function buildDeptTree(rows: Department[], renderExtra: (d: Department) => ReactNode): TreeNode[] {
  const byNo = new Map(rows.map((d) => [d.deptNo, d]));
  const childrenOf = new Map<string, Department[]>();
  for (const d of rows) {
    const key = d.parent && byNo.has(d.parent) ? d.parent : "";
    (childrenOf.get(key) ?? childrenOf.set(key, []).get(key)!).push(d);
  }
  const node = (d: Department): TreeNode => ({
    key: d.deptNo,
    label: (
      <span className="flex flex-wrap items-center gap-2">
        <span className="txt-strong">{d.name}</span>
        <span className="text-xs text-muted-foreground">{d.deptNo}</span>
      </span>
    ),
    extra: renderExtra(d),
    children: bySort(childrenOf.get(d.deptNo) ?? []).map(node),
  });
  return bySort(childrenOf.get("") ?? []).map(node);
}

/** 关键词命中时连**祖先链**一起留下，否则命中的子部门会因为父节点被滤掉而整支消失。 */
function filterDepts(rows: Department[], kw: string): Department[] {
  if (!kw) return rows;
  const byNo = new Map(rows.map((d) => [d.deptNo, d]));
  const keep = new Set<string>();
  for (const d of rows) {
    if (!`${d.deptNo} ${d.name} ${d.leader}`.toLowerCase().includes(kw)) continue;
    for (let cur: Department | undefined = d; cur && !keep.has(cur.deptNo); cur = byNo.get(cur.parent)) keep.add(cur.deptNo);
  }
  return rows.filter((d) => keep.has(d.deptNo));
}

// —— 功能权限勾选树 ——
const PERM_MODULE_LABEL: Record<string, string> = {
  dashboard: "经营看板", device: "设备运营", location: "点位拓展", order: "订单交易",
  // 模块名跟着菜单走：「计费定价」L1 已于 2026-09-23 撤销，其内容并入运营管理 › 计费与调价
  pricing: "计费与调价", finance: "财务分润", workorder: "工单运维", user: "用户运营",
  marketing: "营销", cs: "客服", org: "员工与权限", report: "数据报表",
  system: "系统配置", agent: "代理商管理",
};
/**
 * 资源层中文名：后端 iam_permission 只有 code/module/name，**没有**资源层的名字。
 * 这里从该资源 `:read` 那条的 name 里剥掉动作词得出（"机柜台账 查看" → "机柜台账"），
 * 而不是再手写一份 60 条的资源名映射——两份清单必然漂移，剥词只会跟着目录走。
 */
function resourceLabel(resource: string, items: PermissionItem[]): string {
  const read = items.find((x) => x.code.endsWith(":read")) ?? items[0];
  const name = read?.name ?? "";
  const cut = name.lastIndexOf(" ");
  return cut > 0 ? name.slice(0, cut) : resource;
}
/** 权限码目录 → `<模块>/<资源>/<动作>` 三层树。叶子 key 即权限码（勾选值只认叶子）。 */
function buildPermTree(items: PermissionItem[]): TreeNode[] {
  const byModule = new Map<string, PermissionItem[]>();
  for (const x of items) (byModule.get(x.module) ?? byModule.set(x.module, []).get(x.module)!).push(x);
  return [...byModule].map(([module, mItems]) => {
    const byRes = new Map<string, PermissionItem[]>();
    for (const x of mItems) {
      const res = x.code.split(":")[1] ?? "-";
      (byRes.get(res) ?? byRes.set(res, []).get(res)!).push(x);
    }
    return {
      key: `m:${module}`,
      label: (
        <span className="flex flex-wrap items-center gap-2">
          <span className="txt-strong">{PERM_MODULE_LABEL[module] ?? module}</span>
          <span className="text-xs text-muted-foreground">{module}</span>
        </span>
      ),
      extra: <span className="text-xs text-muted-foreground tabular-nums">{mItems.length} 项</span>,
      children: [...byRes].map(([res, rItems]) => ({
        key: `r:${module}:${res}`,
        label: (
          <span className="flex flex-wrap items-center gap-2">
            <span>{resourceLabel(res, rItems)}</span>
            <span className="text-xs text-muted-foreground">{res}</span>
          </span>
        ),
        children: rItems.map((x) => ({
          key: x.code,
          label: (
            <span className="flex flex-wrap items-center gap-2">
              <span>{x.name}</span>
              <span className="text-xs text-muted-foreground">{x.code}</span>
            </span>
          ),
        })),
      })),
    };
  });
}

function EmployeesInner() {
  const allow = useCan();
  const qc = useQueryClient();
  const tabs = useNavTabs("/employees", TAB_KEYS);
  const { confirm, dialog } = useConfirm();
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位。
  const [showArchived, setShowArchived] = useState(false);
  const paging = usePaging();
  const { tab, setTab } = usePageTab(tabs, () => { paging.reset(); setKeyword(""); setShowArchived(false); });
  // 绩效周期（缺省近 30 日，同报表域）
  const [period, setPeriod] = useState<ReportPeriod>(REPORT_PERIOD_DEFAULT);
  const [keyword, setKeyword] = useState("");
  const [scopeRole, setScopeRole] = useState<RoleRow | null>(null);
  /**
   * 员工级数据范围。后端这个端点本来就是 ROLE|EMPLOYEE 通用的，
   * 而前端此前把 subjectType 写死成 ROLE —— 于是「某个员工要比他的角色看得更窄/更宽」
   * 只能靠给他单开一个角色，而角色是给一类人用的，为一个人开一个会让角色表迅速失去意义。
   */
  const [scopeEmp, setScopeEmp] = useState<Employee | null>(null);
  const [scopeForm, setScopeForm] = useState<ScopeForm>(EMPTY_SCOPE_FORM);
  const [roleForm, setRoleForm] = useState<Partial<RoleRow> | null>(null);
  const [deptForm, setDeptForm] = useState<Partial<Department> | null>(null);
  const [empForm, setEmpForm] = useState<Partial<Employee> | null>(null);
  // 功能权限勾选树。permDraft = 「用户动过手的草稿」，null 表示还没动过 → 显示服务端现值。
  // 不用「打开时 setState 灌一次」那套：react-query 命中缓存时 data 的引用不变，
  // 重开同一个角色的 effect 不会再跑，抽屉里就会出现「一项都没勾」的假象。
  const [permRole, setPermRole] = useState<RoleRow | null>(null);
  const [permDraft, setPermDraft] = useState<string[] | null>(null);
  const [permFilter, setPermFilter] = useState("");
  const [auditId, setAuditId] = useState<string | null>(null);

  const emp = useQuery({
    queryKey: ["employees", paging.page, paging.size, keyword], queryFn: () => api.listEmployees({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "employees",
  });
  // 组织架构整棵拉、不带 keyword：树的过滤必须在前端做（要保留命中节点的祖先链），
  // 交给服务端 keyword 会把父部门滤掉，命中的子部门跟着从树上消失。
  const org = useQuery({
    queryKey: ["departments", "tree"], queryFn: () => api.listDepartments({ page: 1, size: TREE_SIZE }),
    enabled: tab === "org",
  });
  const perf = useQuery({
    queryKey: ["staffPerformance", paging.page, paging.size, keyword, period],
    queryFn: () => api.listStaffPerformance({ page: paging.page, size: paging.size, keyword, period }),
    placeholderData: keepPreviousData, enabled: tab === "performance",
  });
  // showArchived 必须进 queryKey，否则切开关不重新拉数据
  const roles = useQuery({ queryKey: ["roles", showArchived], queryFn: () => api.listRoles({ showArchived }), enabled: tab === "roles" });
  /*
   * 员工表单的角色下拉要用角色表，而上面那个 query 只在「角色」tab 才拉
   * （enabled: tab === "roles"）。单开一个按需的，别去松上面那个的 enabled ——
   * 那会让「员工」tab 每次都白拉一遍角色列表。
   */
  const roleOptsQ = useQuery({
    queryKey: ["role-options"],
    queryFn: () => api.listRoles({}),
    enabled: !!empForm,
  });
  const audit = useQuery({
    queryKey: ["audit", paging.page, paging.size, keyword], queryFn: () => api.listAudits({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepPreviousData, enabled: tab === "audit",
  });

  // 功能权限：目录 + 该角色已分配码（只在抽屉打开时拉）
  const permsQ = useQuery({ queryKey: ["permissions"], queryFn: () => api.listPermissions(), enabled: !!permRole });
  const rolePermsQ = useQuery({
    queryKey: ["role-perms", permRole?.roleNo],
    queryFn: () => api.listRolePermissions(permRole!.roleNo),
    enabled: !!permRole,
  });
  const picked = permDraft ?? rolePermsQ.data ?? [];

  const auditDetailQ = useQuery({
    queryKey: ["audit-detail", auditId], queryFn: () => api.getAuditDetail(auditId!), enabled: !!auditId,
  });

  // 数据权限范围值的候选主数据（只在抽屉打开时拉，避免进页面就多三个请求）
  const scopeOpen = !!scopeRole || !!scopeEmp;
  /*
   * 员工的当前范围**必须先读回来**：员工列表出参不带这个信息，
   * 而保存是整体覆盖 —— 抽屉打开时显示空值、运营点一下保存，原设置就被抹了。
   * 角色不需要这一步（RoleRow 出参已带 dataScope/scopeRefs）。
   */
  const empScopeQ = useQuery({
    queryKey: ["emp-scope", scopeEmp?.employeeNo],
    queryFn: () => api.getDataScope("EMPLOYEE", scopeEmp!.employeeNo),
    enabled: !!scopeEmp,
  });
  useEffect(() => {
    const d = empScopeQ.data;
    if (!d) return;
    setScopeForm({
      dataScope: d.scopeType,
      regionValues: d.scopeType === "REGION" ? (d.scopeRefs ?? "") : "",
      siteValues: d.scopeType === "SITE" ? (d.scopeRefs ?? "") : "",
      agentValues: d.scopeType === "AGENT" ? (d.scopeRefs ?? "") : "",
    });
  }, [empScopeQ.data]);
  const regionsQ = useQuery({ queryKey: ["scope-regions"], queryFn: () => api.listRegions({ page: 1, size: UNPAGED_SIZE }), enabled: scopeOpen });
  const sitesQ = useQuery({ queryKey: ["scope-sites"], queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }), enabled: scopeOpen });
  const agentsQ = useQuery({ queryKey: ["scope-agents"], queryFn: () => api.listAgents({ page: 1, size: UNPAGED_SIZE }), enabled: scopeOpen });
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
        : "后端按 tenant/region/site_no/agent_no 拦截（iam_data_scope）",
    },
    {
      key: "regionValues", label: "可见区域", type: "multiselect", csv: true,
      options: (regionsQ.data?.list ?? []).map((r) => ({ value: r.regionId, label: `${r.name}（${r.regionId}）` })),
      placeholder: "选择区域（可多选）", disabledWhen: (v) => v.dataScope !== "REGION",
      help: "仅「按区域」可选；留空 = 未限定任何区域（后端视为无可见数据）",
    },
    {
      key: "siteValues", label: "可见站点", type: "multiselect", csv: true,
      options: (sitesQ.data?.list ?? []).map((s) => ({ value: s.siteNo, label: `${s.name}（${s.siteNo}）` })),
      placeholder: "选择站点（可多选）", disabledWhen: (v) => v.dataScope !== "SITE",
      help: "仅「按站点」可选；站点下的点位/柜机随站点一起可见（三张表都按 site_no 登记）",
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
    mutationFn: (v: { code: string; form: ScopeForm }) =>
      api.saveDataScope("ROLE", v.code, v.form.dataScope, scopeValuesOf(v.form)),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["roles"] }); notify.success("数据权限已保存"); setScopeRole(null); },
  });
  const saveEmpScope = useMutation({
    mutationFn: (v: { employeeNo: string; form: ScopeForm }) =>
      api.saveDataScope("EMPLOYEE", v.employeeNo, v.form.dataScope, scopeValuesOf(v.form)),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ["emp-scope", v.employeeNo] });
      notify.success("数据权限已保存");
      setScopeEmp(null);
    },
  });
  const saveDept = useMutation({
    mutationFn: (v: Partial<Department>) => api.saveDepartment(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["departments"] }); notify.success("保存成功"); setDeptForm(null); },
  });
  // 功能权限覆盖写：成功后角色列表的 permCount 要跟着变（它就是勾选数），所以两个 key 都失效
  const savePerms = useMutation({
    mutationFn: (v: { roleNo: string; perms: string[] }) => api.saveRolePermissions(v.roleNo, v.perms),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ["roles"] });
      qc.invalidateQueries({ queryKey: ["role-perms", v.roleNo] });
      notify.success(`功能权限已保存（${v.perms.length} 项）`);
      setPermRole(null);
      setPermDraft(null);
    },
  });
  // 上级部门用下拉而不是手输名字：手输既拼不对 deptNo，也挡不住指向不存在的部门（孤儿节点 = 树上少一支）。
  // 编辑时排除自己，避免选出自环。
  const deptFields: FieldDef[] = useMemo(() => [
    { key: "name", label: "部门名称", placeholder: "华东运营部" },
    {
      key: "parent", label: "上级部门", type: "select",
      options: [
        { value: "", label: "（顶级部门）" },
        ...(org.data?.list ?? [])
          .filter((d) => d.deptNo !== deptForm?.deptNo)
          .map((d) => ({ value: d.deptNo, label: `${d.name}（${d.deptNo}）` })),
      ],
      help: "不能选自己的下级——会形成环，服务端同样拦",
    },
    { key: "leader", label: "负责人", placeholder: "张三" },
    { key: "memberCount", label: "成员数", type: "number" },
  ], [org.data, deptForm?.deptNo]);
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
    // 业务号列做扫描锚点（§12.3）：body-strong + 等宽
    { header: "工号", cell: (e) => <span className="txt-strong tabular-nums">{e.employeeNo}</span> },
    { header: "姓名", cell: (e) => e.name },
    { header: "手机", cell: (e) => <span className="text-muted-foreground">{e.phone}</span> },
    { header: "邮箱", cell: (e) => <span className="text-muted-foreground">{e.email}</span> },
    { header: "部门", cell: (e) => <span className="text-muted-foreground">{e.deptName}</span> },
    { header: "角色", cell: (e) => (
      // 显示全部角色而不只是主角色 —— 权限取的是并集，只显示一个会让人以为他只有那些权限
      <div className="flex flex-wrap gap-1">
        <Badge tone="outline">{e.roleName}</Badge>
        {(e.roleNos ?? []).filter((r) => r !== e.roleNo).map((r) => (
          <Badge key={r} tone="muted">{r}</Badge>
        ))}
      </div>
    ) },
    { header: "状态", cell: (e) => e.status === "ACTIVE" ? <Badge tone="success">在职</Badge> : <Badge tone="muted">离职</Badge> },
    {
      header: "操作",
      cell: (e) => canEditEmp ? (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setEmpForm(e)}>编辑</Button>
          {/* 数据范围与功能权限是两件事：功能权限管「能点哪些按钮」，
              数据范围管「同一个按钮下看得见哪些行」。挂在员工行上，
              因为要调的恰恰是「这个人」比他的角色多看或少看。 */}
          <Button size="sm" variant="outline" onClick={() => { setScopeForm(EMPTY_SCOPE_FORM); setScopeEmp(e); }}>数据范围</Button>
        </div>
      ) : <span className="text-muted-foreground">-</span>,
    },
  ];
  const roleCols: Column<RoleRow>[] = [
    { header: "角色码", cell: (r) => <span className="txt-strong">{r.code}</span> },
    { header: "名称", cell: (r) => r.name },
    { header: "权限数", cell: (r) => <span className="tabular-nums">{r.permCount}</span> },
    {
      header: "数据范围",
      cell: (r) => {
        const n = csvCount(r.scopeRefs);
        const needValues = r.dataScope === "REGION" || r.dataScope === "SITE" || r.dataScope === "AGENT";
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
            {/* 功能权限对无 update 权者也开放（只读查看勾选树）：「这个角色到底能干什么」是排障第一问，
                此前只有 permCount 一个数字，谁也答不上来。写侧由抽屉内的按钮再拦一次。 */}
            <Button size="sm" variant="outline" onClick={() => { setPermRole(r); setPermDraft(null); setPermFilter(""); }}>
              功能权限
            </Button>
            {/* 无 org:role:update 时按钮显式禁用（不静默隐藏），范围与数量在「数据范围」列仍可查看 */}
            <Button
              size="sm"
              variant="outline"
              disabled={!canEditRole}
              // 禁用态的悬浮说明不套「仅可查看」那句（那是 ReadOnlyNotice 的句式），只说缺哪个码
              title={canEditRole ? undefined : "缺少 org:role:update，不能修改数据范围"}
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
  // 组织架构改树形（S6 / 拍板点 #4）：层级本身就是信息，扁平表加一列「上级」读者得自己拼。
  const deptRows = org.data?.list ?? [];
  const deptTree = useMemo(
    () => buildDeptTree(filterDepts(deptRows, keyword.trim().toLowerCase()), (d) => (
      <>
        <span className="text-xs text-muted-foreground tabular-nums">{Math.round(d.memberCount)} 人</span>
        <Badge tone="outline">{d.leader}</Badge>
        {canEditDept && <Button size="sm" variant="outline" onClick={() => setDeptForm(d)}>编辑</Button>}
      </>
    )),
    [deptRows, keyword, canEditDept],
  );
  const perfCols: Column<StaffPerformance>[] = [
    { header: "工号", cell: (p) => <span className="txt-strong tabular-nums">{p.employeeNo}</span> },
    { header: "姓名", cell: (p) => p.name },
    { header: "角色", cell: (p) => <Badge tone="outline">{p.role}</Badge> },
    // 数字列右对齐 + 等宽（§12.4）：处理量/时长/评分是要横向比较的量
    { header: "处理量", className: "text-right", cell: (p) => <span className="tabular-nums">{Math.round(p.handled)}</span> },
    { header: "平均解决(分钟)", className: "text-right", cell: (p) => <span className="tabular-nums">{Math.round(p.avgResolveMins)}</span> },
    { header: "评分", className: "text-right", cell: (p) => <span className="txt-strong tabular-nums">{p.handled ? p.score.toFixed(1) : "—"}</span> },
    // 0 单员工出「—」而非「待改进」：绩效从工单派生（buildStaffPerformances），
    // 「没接过单」和「干得差」是两回事，给 0 单的人挂黄标等于冤枉人
    { header: "评价", cell: (p) => p.handled ? <StatusBadge map={PERF_GRADE} value={gradeOf(p.score)} /> : <span className="text-muted-foreground">—</span> },
  ];
  const auditCols: Column<AuditEntry>[] = [
    { header: "时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.createdAt)}</span> },
    // 姓名 + 账号：只给账号看的人得再查一次「omar 是谁」；只给姓名则重名分不清
    { header: "操作人", cell: (a) => a.actorName ? <>{a.actorName} <span className="text-muted-foreground">({a.actor})</span></> : a.actor },
    // 内部调用（SYSTEM:xxx）没有「从哪个端」—— 出短横，别编一个
    { header: "来源", cell: (a) => a.clientCode ? <StatusBadge map={AUDIT_CLIENT} value={a.clientCode} /> : <span className="text-muted-foreground">—</span> },
    { header: "动作", cell: (a) => a.action },
    { header: "对象", cell: (a) => <span className="text-muted-foreground">{a.target}</span> },
    // 这一列原来渲染的是 detail —— 真后端下那是一串 JSON（{"query":…,"changes":[…]}）。
    // 「结果」该回答的是成没成，detail 是明细，两件事。
    { header: "结果", cell: (a) => <StatusBadge map={AUDIT_OUTCOME} value={a.outcome} /> },
    { header: "IP", cell: (a) => <span className="text-muted-foreground">{a.ip}</span> },
    { header: "操作", cell: (a) => <Button size="sm" variant="outline" onClick={() => setAuditId(a.id)}>详情</Button> },
  ];

  // 功能权限勾选树：筛选后重建树。内置角色 + 无 org:role:update 都只读，禁用整棵树而不是隐藏它。
  const permItems = useMemo(() => {
    const f = permFilter.trim().toLowerCase();
    const all = permsQ.data ?? [];
    return f ? all.filter((x) => `${x.code} ${x.name}`.toLowerCase().includes(f)) : all;
  }, [permsQ.data, permFilter]);
  const permTree = useMemo(() => buildPermTree(permItems), [permItems]);
  const permCanWrite = canEditRole && !permRole?.builtin;
  const permReadOnlyWhy = permRole?.builtin ? "内置角色的功能权限只读" : "缺少 org:role:update";

  // org 改树形后不分页（整棵拉），故不进这里
  const paged = tab === "employees" ? emp.data : tab === "performance" ? perf.data : tab === "audit" ? audit.data : undefined;
  const kw = keyword.trim().toLowerCase();
  const roleRows = (roles.data ?? []).filter((r) => !kw || `${r.code} ${r.name}`.toLowerCase().includes(kw));

  if (tabs.length === 0) return <div><PageTitle title="员工与权限" /><EmptyState title="无权限" /></div>;

  const onSearch = (v: string) => { setKeyword(v); paging.reset(); };
  // 导出当页数据（§10.2），列与表格可见列一致。
  const onExportOf = <T,>(name: string, cols: CsvColumn<T>[], rows: T[]) => () => exportCsv<T>(name, cols, rows);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "employees" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={onSearch}
            searchPlaceholder="搜索工号 / 姓名 / 手机 / 邮箱"
            onAdd={canEditEmp ? () => setEmpForm({ name: "", phone: "", email: "", deptName: "", roleNo: "", roleNos: [], status: "ACTIVE" }) : undefined}
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
          {!canEditEmp && <ReadOnlyNotice what="员工维护" perm="org:employee:update" note="不能新增或编辑员工" />}
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
            // CSV 里给上级的中文名（deptNo 对读表的人没有意义），拼不到就退回原值
            { header: "上级部门", value: (d) => deptRows.find((x) => x.deptNo === d.parent)?.name ?? (d.parent || "-") },
            { header: "成员数", value: (d) => Math.round(d.memberCount) },
            { header: "负责人", value: (d) => d.leader },
          ], deptRows)}
        />
      )}
      {tab === "performance" && (
        <Toolbar search={keyword} onSearch={onSearch} searchPlaceholder="搜索工号 / 姓名"
          onExport={onExportOf<StaffPerformance>(`绩效报表-${periodLabel(period)}`, [
            { header: "工号", value: (p) => p.employeeNo },
            { header: "姓名", value: (p) => p.name },
            { header: "角色", value: (p) => p.role },
            { header: "处理量", value: (p) => Math.round(p.handled) },
            { header: "平均解决(分钟)", value: (p) => Math.round(p.avgResolveMins) },
            { header: "评分", value: (p) => (p.handled ? p.score.toFixed(1) : "—") },
            { header: "评价", value: (p) => (p.handled ? PERF_GRADE[gradeOf(p.score)].label : "—") },
          ], perf.data?.list ?? [])}>
          <FilterSelect
            value={period}
            onChange={(v) => { setPeriod(v as ReportPeriod); paging.reset(); }}
            options={REPORT_PERIODS.map((x) => ({ value: x.value, label: x.label }))}
            aria-label="按统计周期筛选"
          />
        </Toolbar>
      )}
      {tab === "roles" && (
        <>
          <Toolbar
            search={keyword}
            onSearch={onSearch}
            searchPlaceholder="搜索角色码 / 名称"
            onAdd={canEditRole ? () => setRoleForm({ code: "", name: "", dataScope: "ALL", scopeRefs: "", permCount: 0, memberCount: 0, builtin: false }) : undefined}
            addLabel="新增角色"
            onExport={onExportOf<RoleRow>("角色", [
              { header: "角色码", value: (r) => r.code },
              { header: "名称", value: (r) => r.name },
              { header: "权限数", value: (r) => r.permCount },
              { header: "数据范围", value: (r) => `${SCOPE_LABEL[r.dataScope]}${csvCount(r.scopeRefs) > 0 ? ` · ${csvCount(r.scopeRefs)} 个` : ""}` },
              { header: "成员数", value: (r) => r.memberCount },
              { header: "类型", value: (r) => (r.builtin ? "内置" : "自定义") },
              ...(showArchived ? [{ header: "归档时间", value: (r: RoleRow) => r.archivedAt ? fmtTime(r.archivedAt) : "-" }] : []),
            ], roleRows)}
          >
            <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} />
          </Toolbar>
          {!canEditRole && <ReadOnlyNotice what="角色维护" perm="org:role:update" note="不能修改角色、功能权限与数据权限" />}
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
      {tab === "employees" && <DataTable rowKey={(e: Employee) => e.employeeNo} columns={empCols} rows={emp.data?.list} loading={emp.isLoading} error={emp.error} onRetry={emp.refetch} empty="暂无员工——换个关键词，或点「新增员工」把运维 / 客服人员录进来。" />}
      {tab === "org" && (
        <Card className="p-2">
          <Tree
            nodes={deptTree}
            loading={org.isLoading}
            empty={keyword ? "没有匹配的部门——换个关键词试试（可搜部门名 / 编号 / 负责人）。" : "暂无部门——点「新增部门」先建顶级部门，再逐级挂下级。"}
          />
        </Card>
      )}
      {tab === "performance" && <DataTable rowKey={(p: StaffPerformance) => p.employeeNo} columns={perfCols} rows={perf.data?.list} loading={perf.isLoading} error={perf.error} onRetry={perf.refetch} empty={`${periodLabel(period)}内没有绩效数据——换个关键词或更长的周期再看。`} />}
      {tab === "roles" && <DataTable rowKey={(r: RoleRow) => r.roleNo} columns={roleCols} rows={roleRows} loading={roles.isLoading} error={roles.error} onRetry={roles.refetch} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的角色——换个关键词，或点「新增角色」建一个自定义角色。" : "暂无在用角色——可能都已归档（打开「显示已归档」查看），或点「新增角色」建第一个自定义角色。"} />}
      {tab === "audit" && <DataTable rowKey={(a: AuditEntry) => a.id} columns={auditCols} rows={audit.data?.list} loading={audit.isLoading} error={audit.error} onRetry={audit.refetch} empty="暂无审计记录——记录在管理员执行写操作后自动产生，换个关键词或时间范围再看。" />}
      {paged && <Pagination page={paging.page} size={paging.size} total={paged.total} onPage={paging.setPage} onSize={paging.setSize} />}

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
        open={!!scopeEmp}
        onOpenChange={(o) => !o && setScopeEmp(null)}
        titleNew="数据权限"
        titleEdit={`数据权限 · ${scopeEmp?.name ?? ""}（${scopeEmp?.employeeNo ?? ""} · 角色 ${scopeEmp?.roleName ?? "-"}）`}
        isEdit
        fields={scopeFields}
        value={scopeForm as unknown as Record<string, unknown>}
        onChange={(v) => setScopeForm(v as unknown as ScopeForm)}
        onSubmit={() => scopeEmp && saveEmpScope.mutate({ employeeNo: scopeEmp.employeeNo, form: scopeForm })}
        submitting={saveEmpScope.isPending || empScopeQ.isLoading}
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
        fields={empFields(roleOptsQ.data ?? [])}
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
        fields={deptFields}
        value={(deptForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setDeptForm(v as Partial<Department>)}
        onSubmit={() => deptForm && saveDept.mutate(deptForm)}
        submitting={saveDept.isPending}
      />

      {/* 功能权限勾选树（S6）：此前只有 permCount 一个数字，「这个角色能干什么」无处可查、更无处可改。 */}
      <Drawer
        open={!!permRole}
        onOpenChange={(o) => !o && setPermRole(null)}
        width="w-[560px]"
        title={`功能权限 · ${permRole?.name ?? ""}`}
        desc={`${permRole?.code ?? ""} · 已选 ${picked.length} / 共 ${permsQ.data?.length ?? 0} 项权限码`}
        footer={
          <>
            <Button variant="outline" onClick={() => setPermRole(null)}>取消</Button>
            <Button
              disabled={!permCanWrite || savePerms.isPending}
              title={permCanWrite ? undefined : permReadOnlyWhy}
              onClick={() => permRole && savePerms.mutate({ roleNo: permRole.roleNo, perms: picked })}
            >
              {savePerms.isPending ? "保存中…" : "保存"}
            </Button>
          </>
        }
      >
        {/* 内置角色只读这条门锁在服务端（IamAdminController 判 builtin=1 直接拒），这里只是提前说清 */}
        {permRole?.builtin && <Notice>内置角色的功能权限只读——登录鉴权依赖这套固定授权，改动请新建自定义角色。</Notice>}
        {!canEditRole && !permRole?.builtin && <ReadOnlyNotice what="角色维护" perm="org:role:update" note="只能查看勾选结果" />}
        <div className="mb-3 flex items-center gap-2">
          <Input
            className="flex-1"
            aria-label="搜索权限码"
            placeholder="搜索权限码 / 名称，如 refund"
            value={permFilter}
            onChange={(e) => setPermFilter(e.target.value)}
          />
          <Button
            size="sm" variant="outline" disabled={!permCanWrite}
            onClick={() => setPermDraft([...new Set([...picked, ...permItems.map((x) => x.code)])])}
          >
            {permFilter ? "全选结果" : "全选"}
          </Button>
          <Button
            size="sm" variant="outline" disabled={!permCanWrite}
            onClick={() => { const drop = new Set(permItems.map((x) => x.code)); setPermDraft(picked.filter((c) => !drop.has(c))); }}
          >
            {permFilter ? "清空结果" : "清空"}
          </Button>
        </div>
        <Tree
          nodes={permTree}
          checkable
          checkedKeys={picked}
          onCheckedChange={setPermDraft}
          disabled={!permCanWrite}
          loading={permsQ.isLoading || rolePermsQ.isLoading}
          // 有筛选词时全展开（否则命中项藏在收起的模块里），否则只显示模块层
          collapseFrom={permFilter ? undefined : 0}
          empty={permFilter ? "没有匹配的权限码——换个关键词（可搜码或中文名）。" : "权限码目录为空——后端 iam_permission 未初始化。"}
        />
      </Drawer>

      {/* 审计详情（S4）：只有列表时「改了什么」全靠猜，这里给字段级前后对比 */}
      <Drawer
        open={!!auditId}
        onOpenChange={(o) => !o && setAuditId(null)}
        width="w-[560px]"
        title="审计详情"
        desc={auditDetailQ.data ? `${auditDetailQ.data.action} · ${auditDetailQ.data.target}` : undefined}
      >
        {auditDetailQ.isLoading && <span className="text-muted-foreground">加载中…</span>}
        {auditDetailQ.data && (
          <>
            <div className="grid grid-cols-2 gap-x-4">
              <Field className="mb-3" label="时间">{fmtTime(auditDetailQ.data.createdAt)}</Field>
              <Field className="mb-3" label="操作人">
                {auditDetailQ.data.actorName ? `${auditDetailQ.data.actorName} (${auditDetailQ.data.actor})` : auditDetailQ.data.actor}
              </Field>
              <Field className="mb-3" label="来源">
                {auditDetailQ.data.clientCode
                  ? <StatusBadge map={AUDIT_CLIENT} value={auditDetailQ.data.clientCode} />
                  : <span className="text-muted-foreground">—</span>}
              </Field>
              <Field className="mb-3" label="动作">{auditDetailQ.data.action}</Field>
              <Field className="mb-3" label="对象">{auditDetailQ.data.target}</Field>
              {/* 原来无论成败都是绿色的「成功」——一条被拒绝的操作看上去和成功的一模一样。 */}
              <Field className="mb-3" label="结果"><StatusBadge map={AUDIT_OUTCOME} value={auditDetailQ.data.outcome} /></Field>
              <Field className="mb-3" label="来源 IP">{auditDetailQ.data.ip}</Field>
              {/* 链路号 = 后端 trace_id。拿它去运行日志 grep %X{traceId} 能看到那次请求的全过程。
                  该列上线前的历史行仍是空 —— 空串渲染成短横，别让它看起来像「长度为 0 的合法号」。 */}
              <Field className="mb-3" label="链路号">
                <span className="break-all font-mono text-xs">{auditDetailQ.data.requestId || "—"}</span>
              </Field>
            </div>
            <Field label="User-Agent">
              <span className="break-all text-xs text-muted-foreground">{auditDetailQ.data.userAgent || "—"}</span>
            </Field>
            <div className="mb-2 text-xs text-muted-foreground">改动前后对比</div>
            {auditDetailQ.data.changes.length === 0 ? (
              <Notice>
                {auditDetailQ.data.outcome === "SUCCESS"
                  ? "该动作不改业务字段（远程指令、导出这类纯动作），只留痕不产生前后对比。"
                  : "这次操作没有做成，因此没有改动任何字段——请求在鉴权/校验处就被挡下了。"}
              </Notice>
            ) : (
              <Table>
                <THead>
                  <TR><TH>字段</TH><TH>改动前</TH><TH>改动后</TH></TR>
                </THead>
                <TBody>
                  {auditDetailQ.data.changes.map((c) => (
                    <TR key={c.field}>
                      <TD>{c.field}</TD>
                      <TD><span className="text-muted-foreground line-through">{c.before}</span></TD>
                      <TD><span className="txt-strong">{c.after}</span></TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}

export default function EmployeesPage() {
  return <Suspense fallback={null}><EmployeesInner /></Suspense>;
}
