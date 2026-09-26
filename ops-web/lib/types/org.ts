// 覆盖范围：组织域（platform）——员工、角色与数据权限、部门、绩效、操作审计。

import type { Archivable } from "./common";

/** 与后端 `EmployeeStatus` 枚举同名同值。具名是为了进两端同名词表比对 ——
 *  这个词表管的是授权（不是 ACTIVE 就一个角色都不给），漂了不会报错。 */
export type EmployeeStatus = "ACTIVE" | "LEFT";
export interface Employee {
  employeeNo: string;
  name: string;
  phone: string;
  email: string; // 登录/通知邮箱
  /** 部门**编号**：编辑抽屉的下拉用它预选，提交也发它。后端写入面只认编号。 */
  deptNo: string | null;
  /** 部门显示名：列表那一列用它。由后端按 deptNo 查 iam_dept 得来，不可提交。 */
  deptName: string | null;
  /** 主角色编号。列表显示用 roleName，**提交必须用它** —— 后端认的是编号。 */
  roleNo: string;
  roleName: string;
  /**
   * 这个人的全部角色（`iam_employee_role`）。**会话权限取它们的并集**。
   *
   * ⚠️ 提交时 `undefined` = 不动角色（改个电话不该把角色清掉），
   * 空数组 = 清掉附加角色（主角色摘不掉）。两者语义不同，别混。
   */
  roleNos: string[];
  status: EmployeeStatus;
}

// —— 角色 · 审计（platform 域）——
/**
 * 数据范围档位。**只列后端「数据范围注册表」真正登记过的**。
 *
 * ⚠️ 这里曾经有 `LOCATION`，而它在 `DataScopeRegistration` 里**一张表都没登记** ——
 * 后端 handler 是 fail-closed（维度找不到锚点列就生成 `1=0`），
 * 所以选了它的人**什么都看不见，而且不报错**。
 * 更糟的是那个档位的选择器给的是「站点」，存下去却是 LOCATION ——
 * 从一开始就没有任何一行数据能匹配上。
 *
 * 真正实现的是 `SITE`（登记在 loc_site / loc_location / dev_cabinet / ord_order / wo_order 五张表）。
 * `VENUE` 同样未登记，故不列。
 *
 * 后端的 `SCOPE_TYPES` 是这里的**超集**（它还接受 LOCATION/VENUE）——
 * 前端不该把后端「接受」的当成「实现了」。DataScopeOptionsTest 盯住这条。
 */
export type DataScope = "ALL" | "REGION" | "SITE" | "AGENT" | "SELF";
/** 数据范围挂在谁身上。后端 SUBJECT_TYPES = ROLE | EMPLOYEE。 */
export type DataScopeSubject = "ROLE" | "EMPLOYEE";

/** 数据范围出参，镜像后端 `DataScopeEntry`。 */
export interface DataScopeEntry {
  subjectType: DataScopeSubject;
  subjectNo: string;
  scopeType: DataScope;
  /** 逗号分隔的 ID；ALL / SELF 语义上不带值，后端落库前会清空。 */
  scopeRefs: string | null;
}

export interface RoleRow extends Archivable {
  roleNo: string;
  code: string;
  name: string;
  permCount: number;
  memberCount: number;
  builtin: boolean;
  dataScope: DataScope; // 数据权限范围
  /**
   * 数据范围的具体取值，逗号分隔的 ID 列表（对应后端 iam_data_scope.scope_refs）：
   * - REGION   → regions.regionId，如 "AE-DU,AE-AZ"
   * - LOCATION → sites.siteNo，如 "ST300,ST305"
   * - AGENT    → agents.agentNo，如 "AG001"
   * - ALL / SELF → 语义上不需要附加值，一律为空串（保存时会被清空）
   */
  scopeRefs?: string;
}
/**
 * 一次被审计的操作成没成。
 *
 * **失败的也记**：被拒绝的操作恰恰是最该留痕的那一类 ——
 * 有人拿没权限的账号反复点某个危险操作是安全信号，
 * 而不记的话，事后查「谁试过改分润规则」得到的答案是「没有人」，
 * 且这个答案和「真的没人试过」长得一模一样。
 */
export type AuditOutcome = "SUCCESS" | "DENIED" | "FAILED";

/** 从哪个端发起。由会话 realm 派生，**不采信请求头**（能被被审计方设置的审计字段比没有更糟）。 */
export type AuditClient = "OPS" | "AGENT" | "MP";

export interface AuditEntry {
  id: string;
  /** 操作人账号/工号。内部调用记 `SYSTEM:<服务名>`。 */
  actor: string;
  /** 操作人姓名（种子行与内部调用可能为 null）。 */
  actorName?: string | null;
  /**
   * 从哪个端做的。运营端与代理端共用 `/api/**` 与同一套审计，
   * 只看 actor 分不清「运营替代理做的」还是「代理自己做的」——
   * 而这是结算争议里第一个被问到的。
   * ⚠️ **历史行为空**：该列上线前没有这个信息，填任何值都是编造。
   */
  clientCode?: AuditClient;
  action: string;
  /** 成没成。历史行一律 SUCCESS —— 此前的实现只在 2xx 时才写。 */
  outcome: AuditOutcome;
  /**
   * 那次请求的链路 id。拿它去运行日志里 grep `%X{traceId}` 能看到那次请求的全过程 ——
   * 审计回答「谁改了什么」，日志回答「那次请求发生了什么」，这是两者之间唯一的那根线。
   * ⚠️ 历史行为空。
   */
  traceId?: string;
  target: string;
  detail: string;
  ip: string;
  createdAt: string;
}
/** 单个字段的改动。新增记录的 before / 删除记录的 after 用 "—"（不是空串，空串是「清空成空值」的真实语义）。 */
export interface AuditFieldChange {
  field: string;
  before: string;
  after: string;
}
/**
 * 审计详情：列表行 + 改动前后对比。
 * `changes` 故意不放进列表响应——一页 10 行、每行几十个字段的 diff 会把列表体积撑爆，
 * 且列表里根本展示不了。点开才拉详情。
 */
export interface AuditDetail extends AuditEntry {
  /**
   * 链路追踪号 = {@link AuditEntry.traceId}（后端 V60 起有 `trace_id` 列）。
   * 此前它恒为空串（表无此列），界面上有这一栏、永远是空的 ——
   * 看的人会以为是数据丢了。现在有值了，但**该列上线前的历史行仍然是空**。
   * 渲染时把空串当「无」处理（短横），不要当成 0 长度的合法追踪号。
   */
  requestId: string;
  /** ⚠️ 同 requestId，表无此列，恒为空串。 */
  userAgent: string;
  /** 后端可能回带的补充字段（种子行为 null）；缺省不影响渲染。
      actorName 已上移到 AuditEntry —— 列表也要显示它，两处各声明一份迟早会分叉。 */
  targetType?: string | null;
  targetNo?: string | null;
  /** 空数组 = 该动作不改字段（远程指令、导出这类纯动作），不是「没记全」。 */
  changes: AuditFieldChange[];
}

/**
 * 权限码目录项。形状对齐后端 `iam_permission`（GET /api/platform/iam/permissions）：
 * code = `<模块>:<资源>:<动作>`，module = 模块前缀，name = 中文名。
 * 权限码 SSOT 是 docs/requirements/功能权限清单.md，后端表为权威副本。
 */
export interface PermissionItem {
  code: string;
  module: string;
  name: string;
}

// —— 员工 · 待建功能补全（platform 域）——
export interface Department {
  deptNo: string;
  name: string;
  /**
   * 同级排序序号（小的在前）。
   *
   * 后端一直在返回，前端没声明 —— 于是组织树的同级顺序**由接口返回顺序决定**，
   * 后台配好的次序（如总部排在分部之前）根本不生效，且换个查询就可能变。
   *
   * ⚠️ 后端还返回 `path`（祖先路径 `/D1/D3/`），**故意不接**：那是后端为子树查询
   * 冗余的列（`LIKE '/D1/%'` 避免递归 CTE），前端是按 parent 客户端建树、
   * filterDepts 也已自行保留祖先链，接了只是把一份后端实现细节固化进契约。
   */
  sort: number;
  /**
   * 上级部门的 **deptNo**（顶级为空串）。
   * 早先存的是部门中文名——名字不保证唯一，改个名就断链，树也就拼不出来；改成引用主键。
   * 约束：必须指向 departments 里真实存在的 deptNo（org-tree.test.ts 断言无孤儿）。
   */
  parent: string;
  memberCount: number;
  leader: string;
  /** 停用的部门不该再出现在「选部门」的下拉里，但档案要留着。 */
  status: "ACTIVE" | "DISABLED" | null;
}
export interface StaffPerformance {
  employeeNo: string;
  name: string;
  role: string;
  /** 统计周期（`2026-09`）。不知道数字覆盖哪段时间，这页的数就没法用。 */
  period: string | null;
  handled: number;
  avgResolveMins: number;
  score: number;
}
