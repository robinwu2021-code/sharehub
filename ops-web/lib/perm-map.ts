/**
 * 前端 UI 能力码 → 后端权限码。
 *
 * <h2>为什么需要这一层</h2>
 * 在它出现之前，判权读的是前端自己写死的 `ROLE_PERMS`，而后端有另一份
 * `RolePerms.java`（类注释还写着「与 ops-web lib/permissions.ts 同源」）。
 * **两份各自演化了很久**：2026-09-23 实测已漂 29 处 —— 26 处「前端渲染入口、后端 403」、
 * 3 处「后端允许、界面上没有入口」。没有任何东西在拦。
 *
 * 机制照搬 ai-shop 的 `ops-web/lib/perm-map.ts`（他们更早踩过同一个坑，已修完）。
 *
 * <h2>这张表担的两件事</h2>
 * 1. **UNIMPLEMENTED 标记** —— 既没有端点判它、也没有角色持有它的码，一律判 false：
 *    **入口不该存在**。让它显示然后点出 404 或 403，比藏起来坏得多。
 *    **当前为 0 条**：D6d 逐条查证后，原先 7 个候选里有 5 个其实是「需要翻译」而非「未实现」
 *    （见下），另 2 个（`org:employee:update` `system:region:update`）端点确实不存在但
 *    功能权限清单列了，属于**待建功能**，留恒等映射以免将来做好时忘了接回来。
 * 2. **翻译** —— 界面功能没有独立端点时映到覆盖它的码。**目前 5 条**（D6d 逐个查证）：
 *
 *    | UI 码 | 实际端点判的码 |
 *    |---|---|
 *    | `location:overview:read` | `location:poi:read` |
 *    | `order:arrears:dun` · `order:deposit:manage` | `order:deposit:update` |
 *    | `order:reservation:cancel` | `order:order:update` |
 *    | `system:notify_log:resend` | `system:notify_log:update` |
 *
 *    这 5 个 UI 码**在后端一个端点都没有**。不翻译的话 `can()` 判 false，
 *    对应的菜单叶与按钮整片消失 —— 「站点概览」「预约取消」「押金处置 / 欠费催缴」
 *    「发送记录重发」都会在界面上不见，而后端其实是允许的。
 *
 * <h2>为什么表里有些码页面从没用过</h2>
 * 来源是**四处的并集**：`nav.ts` 的叶子、页面内联的 `allow(...)`、后端契约里端点实际判的码、
 * 以及 mock 的权限目录 `PERM_CATALOG`。
 * 最后一类现在没有界面入口，但 mock 的权限目录会逐个问它们（`db/org.ts` 的 `roleHas`），
 * 漏登记会让「ADMIN 持有目录全量」这类断言莫名其妙地少几个。
 *
 * <h2>加新码时</h2>
 * 守卫（`perm-map.test.ts`）强制两件事：页面用到的码必须在这张表里、
 * 表里映射到的码必须真的出现在后端契约里。
 * **漏一个的表现都是「按钮神秘消失」** —— 那是最难查的一类。
 */

/** 后端既无端点、也无角色持有。判 false，入口不渲染。 */
export const UNIMPLEMENTED = Symbol("backend-unimplemented");

export const UI_PERM_MAP: Record<string, string | typeof UNIMPLEMENTED> = {
  // ——— agent ———
  "agent:account:manage": "agent:account:manage",
  "agent:agent:create": "agent:agent:create",
  "agent:agent:delete": "agent:agent:delete",
  "agent:agent:read": "agent:agent:read",
  "agent:agent:update": "agent:agent:update",
  "agent:apply:approve": "agent:apply:approve",
  "agent:apply:read": "agent:apply:read",
  "agent:performance:read": "agent:performance:read",
  "agent:scope:assign": "agent:scope:assign",
  "agent:settlement:read": "agent:settlement:read",
  "agent:share:config": "agent:share:config",
  // ——— cs ———
  "cs:session:read": "cs:session:read",
  "cs:session:reply": "cs:session:reply",
  "cs:ticket:handle": "cs:ticket:handle",
  "cs:ticket:read": "cs:ticket:read",
  "cs:ticket:update": "cs:ticket:update",
  // ——— dashboard ———
  "dashboard:overview:read": "dashboard:overview:read",
  "dashboard:todo:read": "dashboard:todo:read",
  // ——— device ———
  "device:cabinet:create": "device:cabinet:create",
  "device:cabinet:delete": "device:cabinet:delete",
  "device:cabinet:export": "device:cabinet:export",
  "device:cabinet:import": "device:cabinet:import",
  "device:cabinet:read": "device:cabinet:read",
  "device:cabinet:update": "device:cabinet:update",
  "device:command:batch": "device:command:batch",
  "device:command:send": "device:command:send",
  "device:inventory:read": "device:inventory:read",
  "device:inventory:stocktake": "device:inventory:stocktake",
  "device:inventory:transfer": "device:inventory:transfer",
  "device:inventory:update": "device:inventory:update",
  "device:ota:manage": "device:ota:manage",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "device:ota:publish": "device:ota:publish",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "device:ota:read": "device:ota:read",
  "device:ota:rollback": "device:ota:rollback",
  "device:powerbank:read": "device:powerbank:read",
  "device:powerbank:update": "device:powerbank:update",
  "device:slot:read": "device:slot:read",
  "device:vendor:config": "device:vendor:config",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "device:vendor:read": "device:vendor:read",
  // ——— finance ———
  "finance:invoice:issue": "finance:invoice:issue",
  "finance:invoice:read": "finance:invoice:read",
  "finance:invoice:update": "finance:invoice:update",
  "finance:invoice:void": "finance:invoice:void",
  "finance:ledger:create": "finance:ledger:create",
  "finance:ledger:read": "finance:ledger:read",
  "finance:recon:handle": "finance:recon:handle",
  "finance:recon:read": "finance:recon:read",
  "finance:recon:resolve": "finance:recon:resolve",
  "finance:reconcile:read": "finance:reconcile:read",
  "finance:settlement:confirm": "finance:settlement:confirm",
  "finance:settlement:generate": "finance:settlement:generate",
  "finance:settlement:read": "finance:settlement:read",
  "finance:share_record:read": "finance:share_record:read",
  "finance:share_rule:config": "finance:share_rule:config",
  "finance:share_rule:create": "finance:share_rule:create",
  "finance:share_rule:delete": "finance:share_rule:delete",
  "finance:share_rule:read": "finance:share_rule:read",
  "finance:share_rule:update": "finance:share_rule:update",
  "finance:withdrawal:apply": "finance:withdrawal:apply",
  "finance:withdrawal:audit": "finance:withdrawal:audit",
  "finance:withdrawal:read": "finance:withdrawal:read",
  "finance:payout_account:read": "finance:payout_account:read",
  "finance:payout_account:update": "finance:payout_account:update",
  // ——— location ———
  "location:analysis:read": "location:analysis:read",
  "location:contract:create": "location:contract:create",
  "location:contract:delete": "location:contract:delete",
  "location:contract:read": "location:contract:read",
  "location:contract:update": "location:contract:update",
  "location:crm:read": "location:crm:read",
  "location:crm:update": "location:crm:update",
  "location:lead:read": "location:lead:read",
  "location:lead:update": "location:lead:update",
  "location:overview:read": "location:overview:read",   // D6d：端点已改判此专属码，恢复恒等
  "location:poi:create": "location:poi:create",
  "location:poi:delete": "location:poi:delete",
  "location:poi:read": "location:poi:read",
  "location:poi:update": "location:poi:update",
  "location:site:update": "location:site:update",
  "location:venue:create": "location:venue:create",
  "location:venue:delete": "location:venue:delete",
  "location:venue:read": "location:venue:read",
  "location:venue:update": "location:venue:update",
  // ——— marketing ———
  "marketing:ad:manage": "marketing:ad:manage",
  "marketing:ad:read": "marketing:ad:read",
  "marketing:ad:update": "marketing:ad:update",
  "marketing:campaign:create": "marketing:campaign:create",
  "marketing:campaign:read": "marketing:campaign:read",
  "marketing:campaign:update": "marketing:campaign:update",
  "marketing:coupon:create": "marketing:coupon:create",
  "marketing:coupon:delete": "marketing:coupon:delete",
  "marketing:coupon:issue": "marketing:coupon:issue",
  "marketing:coupon:read": "marketing:coupon:read",
  "marketing:coupon:update": "marketing:coupon:update",
  "marketing:notice:read": "marketing:notice:read",
  "marketing:notice:update": "marketing:notice:update",
  "marketing:push:send": "marketing:push:send",
  "marketing:push:update": "marketing:push:update",
  "marketing:recharge:update": "marketing:recharge:update",
  "marketing:referral:read": "marketing:referral:read",
  // ——— order ———
  "order:arrears:dun": "order:deposit:update",   // 翻译：界面功能无独立端点 → POST /api/trade/deposits/{no}/dun   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "order:deposit:manage": "order:deposit:update",   // 翻译：界面功能无独立端点 → POST /api/trade/deposits/{no}/buyout · /dun   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "order:deposit:update": "order:deposit:update",
  "order:exception:handle": "order:exception:handle",
  "order:exception:read": "order:exception:read",
  "order:intervene:execute": "order:intervene:execute",
  "order:order:export": "order:order:export",
  "order:order:read": "order:order:read",
  "order:order:update": "order:order:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "order:refund:apply": "order:refund:apply",
  "order:refund:audit": "order:refund:audit",
  "order:reservation:cancel": "order:order:update",   // 翻译：界面功能无独立端点 → POST /api/trade/reservations/{no}/cancel
  // ——— org ———
  "org:audit:read": "org:audit:read",
  "org:dept:create": "org:dept:create",
  "org:dept:delete": "org:dept:delete",
  "org:dept:read": "org:dept:read",
  "org:dept:update": "org:dept:update",
  "org:employee:create": "org:employee:create",
  "org:employee:delete": "org:employee:delete",
  "org:employee:read": "org:employee:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "org:employee:update": "org:employee:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "org:performance:read": "org:performance:read",
  "org:role:assign": "org:role:assign",
  "org:role:read": "org:role:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "org:role:update": "org:role:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  // ——— pricing ———
  "pricing:adjustment:cancel": "pricing:adjustment:cancel",
  "pricing:adjustment:create": "pricing:adjustment:create",
  "pricing:adjustment:read": "pricing:adjustment:read",
  "pricing:plan:create": "pricing:plan:create",
  "pricing:plan:delete": "pricing:plan:delete",
  "pricing:plan:read": "pricing:plan:read",
  "pricing:plan:update": "pricing:plan:update",
  "pricing:rule:config": "pricing:rule:config",
  "pricing:rule:read": "pricing:rule:read",
  "pricing:rule:update": "pricing:rule:update",
  // ——— report ———
  "report:consumer:read": "report:consumer:read",
  "report:custom:read": "report:custom:read",
  "report:device:export": "report:device:export",
  "report:device:read": "report:device:read",
  "report:finance:export": "report:finance:export",
  "report:finance:read": "report:finance:read",
  "report:location:export": "report:location:export",
  "report:location:read": "report:location:read",
  "report:screen:read": "report:screen:read",
  // ——— system ———
  "system:app_version:read": "system:app_version:read",
  "system:app_version:release": "system:app_version:release",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:app_version:update": "system:app_version:update",
  "system:brand:read": "system:brand:read",
  "system:brand:update": "system:brand:update",
  "system:bank:read": "system:bank:read",
  "system:bank:update": "system:bank:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:biz_rule:read": "system:biz_rule:read",
  "system:biz_rule:update": "system:biz_rule:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:dict:read": "system:dict:read",
  "system:dict:update": "system:dict:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:login_setting:read": "system:login_setting:read",
  "system:login_setting:update": "system:login_setting:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:market:read": "system:market:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:market:update": "system:market:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:notify_blacklist:read": "system:notify_blacklist:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:notify_blacklist:update": "system:notify_blacklist:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:notify_log:read": "system:notify_log:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:notify_log:resend": "system:notify_log:update",   // 翻译：界面功能无独立端点 → POST /api/platform/notify-logs/{no}/resend   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:notify_log:update": "system:notify_log:update",
  "system:notify_template:read": "system:notify_template:read",
  "system:notify_template:update": "system:notify_template:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:openapi:config": "system:openapi:config",
  "system:openapi:read": "system:openapi:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:openapi:update": "system:openapi:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:param:read": "system:param:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:param:update": "system:param:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:payment_channel:read": "system:payment_channel:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:payment_channel:update": "system:payment_channel:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:problem:read": "system:problem:read",
  "system:problem:update": "system:problem:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:region:read": "system:region:read",
  "system:region:update": "system:region:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "system:tax:read": "system:tax:read",
  "system:tax:update": "system:tax:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  // ——— user ———
  "user:cuser:read": "user:cuser:read",
  "user:member:read": "user:member:read",
  "user:member:update": "user:member:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "user:risk:read": "user:risk:read",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  "user:risk:update": "user:risk:update",
  "user:wallet:read": "user:wallet:read",
  "user:wallet:update": "user:wallet:update",   // ⚠️ D6d：后端无角色持有 → 除 ADMIN 外必 403
  // ——— workorder ———
  "workorder:alarm:config": "workorder:alarm:config",
  "workorder:alarm:notice_resend": "workorder:alarm:notice_resend",
  "workorder:alarm:update": "workorder:alarm:update",
  "workorder:inspection:update": "workorder:inspection:update",
  "workorder:sla:update": "workorder:sla:update",
  "workorder:wo:audit": "workorder:wo:audit",
  "workorder:wo:close": "workorder:wo:close",
  "workorder:wo:create": "workorder:wo:create",
  "workorder:wo:dispatch": "workorder:wo:dispatch",
  "workorder:wo:handle": "workorder:wo:handle",
  "workorder:wo:process": "workorder:wo:process",
  "workorder:wo:read": "workorder:wo:read",
  "workorder:wo:update": "workorder:wo:update",
};
