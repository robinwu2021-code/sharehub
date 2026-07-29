import type { Role } from "./auth";

// 细粒度权限码（`<模块>:<资源>:<动作>`），对齐 docs/requirements/功能权限清单.md（后端为权威）。
// 支持通配：'*' 全部；'device:*' 该模块全部。角色→权限码映射如下。

const ROLE_PERMS: Record<Role, string[]> = {
  ADMIN: ["*"],

  OPS: [
    "dashboard:overview:read", "dashboard:todo:read",
    "device:cabinet:*", "device:slot:read", "device:powerbank:*",
    "device:command:*", "device:inventory:*", "device:ota:read", "device:vendor:read",
    "location:poi:read", "location:venue:read",
    "order:order:read", "order:exception:read", "order:exception:handle",
    "workorder:*",
    "agent:scope:assign",
    "report:device:read", "report:location:read",
    "system:notify_template:read", "system:dict:read",
    // 对标补齐（功能权限清单 §13）：运维要能查发送记录与 App 版本
    "system:notify_log:read", "system:app_version:read", "system:param:read", "system:market:read",
  ],
  CS: [
    "dashboard:overview:read", "dashboard:todo:read",
    "device:cabinet:read", "device:slot:read", "device:command:send",
    "order:order:read", "order:order:export", "order:exception:read", "order:exception:handle",
    "order:intervene:execute", "order:refund:apply",
    "user:cuser:read", "user:risk:update", "user:member:read", "user:wallet:read",
    "workorder:wo:read", "workorder:wo:create",
    "cs:*",
    "marketing:coupon:read", "marketing:coupon:issue", "marketing:push:send",
    // 对标补齐（功能权限清单 §13）：客服要能查发送记录、拦截骚扰、维护问题字典
    "system:notify_log:read", "system:notify_blacklist:read", "system:notify_blacklist:update",
    "system:problem:read", "system:problem:update",
  ],

  FINANCE: [
    "dashboard:overview:read", "dashboard:todo:read",
    "order:order:read", "order:order:export", "order:refund:audit",
    "pricing:*", "finance:*",
    "agent:agent:read", "agent:share:config", "agent:settlement:read", "agent:performance:read",
    "location:venue:read", "location:contract:read", "location:analysis:read",
    "user:cuser:read", "user:member:read", "user:wallet:read",
    "report:*", "org:audit:read",
    // 对标补齐（功能权限清单 §13）：提现口径/收款方字典/税率归财务
    "system:biz_rule:update", "system:bank:read", "system:bank:update",
    "system:tax:update", "system:payment_channel:read",
  ],

  BD: [
    "dashboard:overview:read",
    "location:*", "pricing:rule:read", "pricing:rule:update",
    "marketing:*", "agent:*",
    "finance:share_rule:read", "finance:share_record:read",
    "report:*",
  ],

  VIEWER: [
    "dashboard:overview:read", "dashboard:todo:read",
    "device:cabinet:read", "device:slot:read", "order:order:read",
    "location:poi:read", "location:venue:read", "location:analysis:read",
    "finance:share_rule:read", "finance:share_record:read", "finance:settlement:read", "finance:withdrawal:read",
    "report:*",
  ],

  // 代理端：只读自己范围（数据权限在后端按 agent_no 收敛）
  AGENT: [
    "dashboard:overview:read",
    "device:cabinet:read", "device:slot:read",
    "workorder:wo:create", "order:order:read",
    "finance:share_record:read", "finance:withdrawal:apply",
    "agent:settlement:read", "location:poi:read",
  ],
};

function match(pattern: string, code: string): boolean {
  if (pattern === code) return true;
  if (pattern.endsWith("*")) return code.startsWith(pattern.slice(0, -1)); // '*' 或 'device:*'
  return false;
}

/** 按钮/操作级鉴权：角色是否拥有该权限码。 */
export function can(role: Role | undefined, code: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => match(p, code)) ?? false;
}

/** 模块级（导航/页面）：角色对某模块前缀是否有任一权限。 */
export function canModule(role: Role | undefined, module: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => p === "*" || p === module || p.startsWith(module + ":")) ?? false;
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
