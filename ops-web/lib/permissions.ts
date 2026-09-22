import type { Role } from "./auth";

// 细粒度权限码（`<模块>:<资源>:<动作>`），对齐 docs/requirements/功能权限清单.md（后端为权威）。
// 支持通配：'*' 全部；'device:*' 该模块全部。角色→权限码映射如下。

const ROLE_PERMS: Record<Role, string[]> = {
  ADMIN: ["*"],

  OPS: [
    "dashboard:overview:read", "dashboard:todo:read",
    // 运营管理·站点概览（2026-09-22）：看站点/设备铺设与待关注站点，运维主责
    "location:overview:read",
    "device:cabinet:*", "device:slot:read", "device:powerbank:*",
    // OTA 给整模块通配：后端 GET/POST /ota-releases 判 device:ota:manage、投放编辑判
    // device:ota:publish，二者此前都没登记 —— 只有 ADMIN 靠 '*' 能用，OPS 作为运维主责
    // 却连固件版本库入口都不渲染。固件写权仅给 OPS，CS/FINANCE/BD/VIEWER/AGENT 一律不给。
    "device:command:*", "device:inventory:*", "device:ota:*", "device:vendor:read",
    "location:poi:read", "location:venue:read",
    "order:order:read", "order:exception:read", "order:exception:handle",
    "order:reservation:cancel",
    // OPS 是工单主责，工单域全量：wo:read/create/dispatch/handle(接单·处理·完成)/close(验收关单)
    // + sla:update / inspection:update / alarm:config（对应功能权限清单 §7）
    // + alarm:notice_resend（重发通知会真的再发一条，故与 alarm:config 分开发码；
    //   刻意不复用 system:notify_log:resend —— 那个码 CS 也持有，客服不该能把设备告警
    //   重新轰炸给值班工程师）
    "workorder:*",
    "agent:scope:assign",
    "report:device:read", "report:location:read",
    "system:notify_template:read", "system:dict:read",
    // 对标补齐（功能权限清单 §13）：运维要能查发送记录与 App 版本
    // notify_log:resend 为 S7 新增：重发是**会真的再发一次**的动作，故与只读分开发码
    "system:notify_log:read", "system:notify_log:resend",
    "system:app_version:read", "system:param:read", "system:market:read",
  ],
  CS: [
    "dashboard:overview:read", "dashboard:todo:read",
    // 公告只读（2026-09-22）：旧菜单公告挂在 marketing:coupon:read 下、CS 原本可见，保持可见性不变；
    // 发布/下线（:update）不给客服。后端早已按 marketing:notice:* 判权
    "marketing:notice:read",
    "device:cabinet:read", "device:slot:read", "device:command:send",
    "order:order:read", "order:order:export", "order:exception:read", "order:exception:handle",
    "order:intervene:execute", "order:refund:apply",
    // 催缴给客服（只留痕不改钱），解冻/买断不给（动钱，归财务）——见功能权限清单 §4 注
    "order:reservation:cancel", "order:arrears:dun",
    "user:cuser:read", "user:risk:update", "user:member:read", "user:wallet:read",
    "workorder:wo:read", "workorder:wo:create",
    "cs:*",
    "marketing:coupon:read", "marketing:coupon:issue", "marketing:push:send",
    // 对标补齐（功能权限清单 §13）：客服要能查发送记录、拦截骚扰、维护问题字典
    "system:notify_log:read", "system:notify_log:resend",
    "system:notify_blacklist:read", "system:notify_blacklist:update",
    "system:problem:read", "system:problem:update",
  ],

  FINANCE: [
    "dashboard:overview:read", "dashboard:todo:read",
    "location:overview:read", // 运营管理·站点概览；预约调价 pricing:adjustment:* 已被 pricing:* 覆盖
    "order:order:read", "order:order:export", "order:refund:audit",
    "order:deposit:manage", "order:arrears:dun",
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
    "location:overview:read", // 运营管理·站点概览（只读角色）
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
