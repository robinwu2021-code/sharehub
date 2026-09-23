import type { Role } from "./auth";
import { UI_PERM_MAP, UNIMPLEMENTED } from "./perm-map";

// 细粒度权限码（`<模块>:<资源>:<动作>`），对齐 docs/requirements/功能权限清单.md（后端为权威）。
// 支持通配：'*' 全部；'device:*' 该模块全部。角色→权限码映射如下。

/**
 * 后端角色表的**前端镜像**。
 *
 * ⚠️ **它不再是判权依据**（2026-09-23，D6a）。判权读的是后端 `GET /api/auth/me` 下发的
 * `perms`，见 {@link can}。这份镜像只剩两个用途：
 *
 * 1. **mock 模式**（`NEXT_PUBLIC_USE_MOCK != 0`）—— 离线开发时没有后端可下发，按角色展开；
 * 2. **对账** —— `permissions.test.ts` 拿它与后端 `RolePerms.java` 比对，漂了就红。
 *
 * 它曾经是判权真源，代价已经付过：与后端那份**各自演化到漂了 29 处**
 * （26 处前端放行·后端 403，3 处后端允许·界面没入口），而两边的注释都写着「同源」。
 */
export const BACKEND_ROLE_PERMS: Record<Role, string[]> = {
  // 超管：不走权限表 —— 新增权限点时自动拥有。反过来做（逐项列给超管）的后果是
  // 上一个新功能就把唯一能授权的人锁在外面。
  ADMIN: [
    "*",
  ],
  // 运维：设备、工单、巡检、OTA、系统参数。
  OPS: [
    "dashboard:overview:read", "dashboard:todo:read", "device:cabinet:*", "device:slot:read",
    "device:powerbank:*", "device:command:*", "device:inventory:*", "device:ota:*",
    "device:vendor:read", "location:poi:read", "location:venue:read", "location:overview:read",
    "order:order:read", "order:exception:read", "order:exception:handle", "workorder:*",
    "agent:scope:assign", "report:device:read", "report:location:read",
    "system:notify_template:read", "system:dict:read", "system:notify_log:read",
    "system:app_version:read", "system:market:read", "system:param:read",
  ],
  // 客服：订单干预、用户、会话、通知、问题字典。**不碰钱**。
  CS: [
    "dashboard:overview:read", "dashboard:todo:read", "device:cabinet:read", "device:slot:read",
    "device:command:send", "order:order:read", "order:order:export", "order:exception:read",
    "order:exception:handle", "order:intervene:execute", "order:refund:apply",
    "user:cuser:read", "user:risk:update", "user:member:read", "user:wallet:read",
    "workorder:wo:read", "workorder:wo:create", "cs:*", "marketing:coupon:read",
    "marketing:coupon:issue", "marketing:push:send", "marketing:notice:read",
    "system:notify_log:read", "system:notify_blacklist:read", "system:notify_blacklist:update",
    "system:problem:read", "system:problem:update",
  ],
  // 财务：计价、分润、结算、提现、银行字典、税率、支付渠道。
  FINANCE: [
    "dashboard:overview:read", "dashboard:todo:read", "order:order:read", "order:order:export",
    "order:refund:audit", "pricing:*", "finance:*", "agent:agent:read", "agent:share:config",
    "agent:settlement:read", "agent:performance:read", "location:venue:read",
    "location:contract:read", "location:analysis:read", "location:overview:read",
    "user:cuser:read", "user:member:read", "user:wallet:read", "report:*", "org:audit:read",
    "system:bank:read", "system:bank:update", "system:tax:update", "system:biz_rule:update",
    "system:payment_channel:read",
  ],
  // 拓展：场地方、代理商、营销、报表。
  BD: [
    "dashboard:overview:read", "location:*", "pricing:rule:read", "pricing:rule:update",
    "marketing:*", "agent:*", "finance:share_rule:read", "finance:share_record:read",
    "report:*",
  ],
  // 只读。
  VIEWER: [
    "dashboard:overview:read", "dashboard:todo:read", "device:cabinet:read", "device:slot:read",
    "order:order:read", "location:poi:read", "location:venue:read", "location:analysis:read",
    "location:overview:read", "finance:share_rule:read", "finance:share_record:read",
    "finance:settlement:read", "finance:withdrawal:read", "agent:agent:read", "report:*",
  ],
  // 代理商：**强制数据范围 = 自己的主体**，看到什么由数据范围再收一道。
  AGENT: [
    "dashboard:overview:read", "device:cabinet:read", "device:slot:read", "workorder:wo:create",
    "workorder:wo:read", "order:order:read", "finance:share_record:read",
    "finance:withdrawal:apply", "agent:settlement:read", "location:poi:read",
  ],
};

function match(pattern: string, code: string): boolean {
  if (pattern === code) return true;
  if (pattern.endsWith("*")) return code.startsWith(pattern.slice(0, -1)); // '*' 或 'device:*'
  return false;
}

/** 按钮/操作级鉴权：角色是否拥有该权限码。 */
/**
 * 「可能没有角色」—— 未登录（空串）与未知（undefined）在鉴权上是同一件事：**都没有权限**。
 *
 * 之所以让空串进类型而不是在 11 个调用点各写一次 `role || undefined`：
 * 下面每个消费者运行时本来就 fail-closed（`if (!role) return false`），
 * 把"没有身份"表达进类型，比让每个调用方记得转换要可靠 —— 漏转一处不会报错，
 * 只会让未登录状态走进某个 `role === "ADMIN"` 分支。
 */
export type MaybeRole = Role | "" | undefined;

/**
 * 按钮/操作级鉴权。
 *
 * **入参是后端下发的 `perms`，不是 `role`** —— 后者只用于展示与菜单分组。
 * 空数组 = 零权限（**不是「还没加载」**：未登录时本来就该什么都看不见）。
 */
export function can(perms: string[] | undefined, code: string): boolean {
  if (!perms?.length) return false;

  /*
   * **先查映射、后判通配**。反过来的话超管会看到 UNIMPLEMENTED 的入口 ——
   * 而那些端点根本不存在，他点下去同样 404。
   * 「超管什么都能做」说的是权限，不是「后端还没写的功能他也能用」。
   */
  const mapped = UI_PERM_MAP[code];
  if (mapped === undefined) {
    // 漏登记。守卫（perm-map.test.ts）会在 CI 拦住，这里是运行时兜底：
    // **拒绝优先** —— 默认放行会静默开一个没人知道的口子
    if (process.env.NODE_ENV !== "production") {
      console.warn(`[perm] UI 码未登记进 UI_PERM_MAP，按无权限处理：${code}`);
    }
    return false;
  }
  if (mapped === UNIMPLEMENTED) return false;
  if (perms.includes("*")) return true;
  return perms.some((p) => match(p, mapped));
}

/** 模块级（导航/页面）：对某模块前缀是否有任一权限。 */
export function canModule(perms: string[] | undefined, module: string): boolean {
  if (!perms?.length) return false;
  const prefix = module.toLowerCase() + ":";
  const codes = Object.keys(UI_PERM_MAP).filter((ui) => ui.toLowerCase().startsWith(prefix));
  /*
   * **这个模块下一个受控码都没有 = 它不受权限约束**（看板就是这样：
   * 登录后的首页，人人可见）。返回 false 的话，所有人登录后落到空导航。
   *
   * 与「UNIMPLEMENTED 不可见」不冲突：那是「这块能力后端没有」，
   * 这是「这块本来就不需要权限」。两者都不该靠 canModule 猜。
   */
  if (!codes.length) return true;
  // 超管也不例外：整域未开工的模块，菜单不该出现
  return codes.some((ui) => can(perms, ui));
}

/** 角色视角的判权 —— **仅供 mock 与对账**，不要用在页面里。 */
export function roleHas(role: MaybeRole, code: string): boolean {
  if (!role) return false;
  return can(BACKEND_ROLE_PERMS[role], code);
}

/** 角色 → 权限码展开。mock 模式下登录后写进 store 的就是它。 */
export function permsOf(role: MaybeRole): string[] {
  return role ? (BACKEND_ROLE_PERMS[role] ?? []) : [];
}

/**
 * 「谁在看」—— 判权用 {@link Viewer.perms}，挑门户 section 用 {@link Viewer.role}。
 *
 * **为什么打包成一个对象而不是两个参数**：本项目的 `NavSection.portalFor` 让
 * 「判权」与「分组」必须同行（代理门户与运营 section 共用同一批路径，
 * 不按 role 限定的话运营人员的面包屑会变成「我的经营 › …」）。
 * 两个都是可选参数时**传反了不会报错**，只会让菜单静默变成另一个人的样子。
 */
export interface Viewer {
  perms: string[] | undefined;
  role: MaybeRole;
}

/**
 * 可以直接传一个角色名。
 *
 * 这条**只给测试与 mock 用**：它按 {@link BACKEND_ROLE_PERMS} 展开权限，
 * 语义正是「角色 X 的权限集合下，菜单长什么样」—— 测试想验的就是这个。
 * **生产代码一律传 {@link Viewer}**，因为真实权限来自后端下发，不是本地镜像。
 */
export type ViewerLike = Viewer | MaybeRole;

export function asViewer(v: ViewerLike): Viewer {
  return (v === undefined || typeof v === "string")
    ? { role: v, perms: permsOf(v) }
    : v;
}

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: "运营管理员",
  OPS: "运维",
  CS: "客服",
  FINANCE: "财务",
  BD: "拓展",
  VIEWER: "只读",
  AGENT: "代理商",
};
