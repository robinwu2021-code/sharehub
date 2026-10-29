import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { NAV } from "./nav";
import { tNav } from "./i18n/nav-labels";

/**
 * 菜单真源从 `nav.ts` 迁到 `iam_menu`（V67 建表灌数，V71 补 section 码，V74 补叶子码）——**切换那一刻两边必须逐节点相等**。
 *
 * <h3>为什么非要有这一条</h3>
 * 迁移里那 127 行 INSERT 是 `backend/scripts/gen-menu-seed.py` 生成的。
 * 手写做不到「相等」，只能做到「看起来差不多」——
 * 而差掉的那几行**不会有任何东西报错**：菜单少一项，用的人以为没这功能。
 *
 * 迁移前那张表已经示范过一次了：生产 `iam_menu` 只有 12 行、零叶子，
 * 其中两个菜单 nav.ts 里根本不存在、另外五个顶级菜单缺失 ——
 * 它是首次启动灌的存货，此后 nav.ts 改了无数次，一直没人发现。
 *
 * <h3>红了怎么办</h3>
 * 说明 nav.ts 改了而迁移没跟上。**不要手改迁移**，重新生成：
 * ```
 * cd ops-web && npx vitest run lib/nav-seed.test.ts   # 看差在哪
 * # 改完 nav.ts 后重新导出并生成，步骤见 gen-menu-seed.py 的文档串
 * ```
 * **永远新开一个迁移，不要改已提交的那个** —— 别人的库可能已经跑过它，
 * 改了 Flyway 会因校验和不符拒绝启动。V71 就是这么来的。
 */

// 相对**仓库根**，不是相对本文件 —— vitest 的 cwd 是 ops-web/
const MIGRATION = "../backend/sharehub-app/src/main/resources/db/migration/V89__menu_consumer_queue_leaves.sql";

type Row = Record<string, string>;

/**
 * V89 之后**只改个别字段**的菜单迁移（不整表重灌 —— 那会冲掉「菜单管理」里的线上改动）。
 * 每条补丁都要：①断言迁移文件里真有这条 UPDATE（防止补丁与迁移各说各话）；②在解析出的行上重放。
 * 新增补丁迁移时在这里加一项；种子整表重生成后清空本列表并把 MIGRATION 指向新文件。
 */
const MIGRATION_DIR = "../backend/sharehub-app/src/main/resources/db/migration/";
const PATCHES: { file: string; mustContain: string; apply: (rows: Row[]) => void }[] = [
  {
    // 放开客服两叶（生产跑在 L0，这两页点不开，而 C 端报障全落在这里）。
    file: "V140__menu_cs_leaves_ready.sql",
    mustContain: "UPDATE iam_menu SET ready = 1 WHERE menu_no IN ('M_cs__1', 'M_cs__2')",
    apply: (rows) => {
      for (const r of rows) {
        if (r.menu_no === "M_cs__1" || r.menu_no === "M_cs__2") r.ready = "1";
      }
    },
  },
  {
    // 流程与工单 L1 + 7 叶（OpsKit 运营界面）。放在 nav.ts **最末**，只插新行、不动任何既有 sort。
    file: "V136__menu_flow_section.sql",
    mustContain: "'M_flow' AS menu_no",
    apply: (rows) => {
      rows.push(
        { menu_no: "M_flow", parent_no: "NULL", name: "流程与工单", name_en: "Workflow & Tickets", name_ar: "سير العمل والتذاكر",
          type: "MENU", path: "/flow/todo", icon: "Workflow", group_name: "NULL", sort: "19", perm: "NULL", phase: "1", ready: "0",
          module: "flow", modules: "NULL", match_paths: '["/flow"]', pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__1", parent_no: "M_flow", name: "待办中心", name_en: "My Tasks", name_ar: "مهامي", type: "ITEM",
          path: "/flow/todo", icon: "NULL", group_name: "我的待办", sort: "1", perm: "flow:todo:read", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__2", parent_no: "M_flow", name: "审批列表", name_en: "Approvals", name_ar: "الموافقات", type: "ITEM",
          path: "/flow/approvals", icon: "NULL", group_name: "审批", sort: "2", perm: "flow:approval:read", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__3", parent_no: "M_flow", name: "审核队列", name_en: "Review Queue", name_ar: "قائمة المراجعة", type: "ITEM",
          path: "/flow/queue", icon: "NULL", group_name: "审批", sort: "3", perm: "flow:queue:claim", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__4", parent_no: "M_flow", name: "工单工作台", name_en: "Ticket Desk", name_ar: "مكتب التذاكر", type: "ITEM",
          path: "/flow/tickets", icon: "NULL", group_name: "工单", sort: "4", perm: "flow:ticket:read", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__5", parent_no: "M_flow", name: "流程配置", name_en: "Approval Templates", name_ar: "قوالب الموافقة", type: "ITEM",
          path: "/flow/processes", icon: "NULL", group_name: "配置", sort: "5", perm: "flow:process:manage", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__6", parent_no: "M_flow", name: "运营配置", name_en: "SLA & Queues", name_ar: "اتفاقيات الخدمة والقوائم", type: "ITEM",
          path: "/flow/settings", icon: "NULL", group_name: "配置", sort: "6", perm: "flow:config:manage", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__7", parent_no: "M_flow", name: "接入管理", name_en: "Integrations", name_ar: "التكاملات", type: "ITEM",
          path: "/flow/access", icon: "NULL", group_name: "配置", sort: "7", perm: "flow:access:manage", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
      );
    },
  },
  {
    // 用户管理 › 会员套餐（mbr_plan 此前没有任何写入方，C 端会员页因此永远是空列表）。
    // 是 INSERT 而不是整表重灌 —— 故这条叶子在 nav.ts 里刻意放在本菜单末尾，
    // 往组中间插会让同组后面所有叶子的 sort 都要挪。
    file: "V132__menu_member_plan_leaf.sql",
    mustContain: "'M_user__10', 'M_user', '会员套餐'",
    apply: (rows) => {
      rows.push({
        menu_no: "M_user__10", parent_no: "M_user", name: "会员套餐",
        name_en: "Membership Plans", name_ar: "خطط العضوية", type: "ITEM",
        path: "/users?tab=plans", icon: "NULL", group_name: "用户资产", sort: "10",
        perm: "user:member:read", phase: "2", ready: "0",
        module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 告警四个菜单叶：workorder:wo:read → workorder:alarm:read
    file: "V92__menu_alarm_perm.sql",
    mustContain: "UPDATE iam_menu SET perm = 'workorder:alarm:read'",
    apply: (rows) => {
      const paths = ["/alarms", "/alarms?tab=notices", "/alarms?tab=codes", "/alarms?tab=rules"];
      for (const r of rows) {
        if (paths.includes(r.path) && r.perm === "workorder:wo:read") r.perm = "workorder:alarm:read";
      }
    },
  },
  {
    // 流程与工单 › 流转与统计：流程实例 · 流程统计（OpsKit O2-10）。追加在 V136 七个叶子之后（sort 8、9），不动既有 sort。
    // 迁移号 V139：V138 被另一会话的在途文件占用。
    file: "V139__menu_flow_instances_stats.sql",
    mustContain: "'M_flow__8' AS menu_no",
    apply: (rows) => {
      rows.push(
        { menu_no: "M_flow__8", parent_no: "M_flow", name: "流程实例", name_en: "Process Instances", name_ar: "مثيلات المسارات", type: "ITEM",
          path: "/flow/instances", icon: "NULL", group_name: "流转与统计", sort: "8", perm: "flow:approval:read", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_flow__9", parent_no: "M_flow", name: "流程统计", name_en: "Process Stats", name_ar: "إحصاءات المسارات", type: "ITEM",
          path: "/flow/stats", icon: "NULL", group_name: "流转与统计", sort: "9", perm: "flow:process:manage", phase: "1", ready: "0",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
      );
    },
  },
  {
    // 代理商管理 → 商家管理，按运营顺序重排（OM01）。按旧编号定点更新、互换分润与划拨两行，
    // 撤「代理收益结算」深链，插经营视图与退出与清算两叶。重放顺序与迁移语句一致。
    file: "V148__menu_agent_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = 'M_agent__x4'",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      Object.assign(at("M_agent"), { name: "商家管理", name_en: "Merchants", name_ar: "التجار" });
      rows.splice(rows.findIndex((r) => r.menu_no === "M_agent__6" && r.path === "/finance?tab=settlements"), 1);
      Object.assign(at("M_agent__1"), { group_name: "入驻与档案", sort: "1" });
      Object.assign(at("M_agent__2"), { name: "商家档案", name_en: "Merchant Profiles", name_ar: "ملفات التجار", group_name: "入驻与档案", sort: "2" });
      Object.assign(at("M_agent__3"), { name: "商家账号", name_en: "Merchant Accounts", name_ar: "حسابات التجار",
        perm: "agent:account:manage", group_name: "入驻与档案", sort: "3" });
      const assign = at("M_agent__4");
      const commission = at("M_agent__5");
      Object.assign(commission, { menu_no: "M_agent__4", name: "商家分润规则", name_en: "Merchant Commission", name_ar: "عمولة التجار",
        group_name: "分润与划拨", sort: "4" });
      Object.assign(assign, { menu_no: "M_agent__5", group_name: "分润与划拨", sort: "5" });
      Object.assign(at("M_agent__7"), { name: "商家绩效", name_en: "Merchant Performance", name_ar: "أداء التجار", group_name: "经营与退出", sort: "7" });
      rows.push(
        { menu_no: "M_agent__6", parent_no: "M_agent", name: "商家经营视图", name_en: "Merchant Business", name_ar: "أعمال التجار", type: "ITEM",
          path: "/agents?tab=business", icon: "NULL", group_name: "经营与退出", sort: "6", perm: "agent:agent:read", phase: "1", ready: "1",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_agent__8", parent_no: "M_agent", name: "退出与清算", name_en: "Exit & Liquidation", name_ar: "الخروج والتصفية", type: "ITEM",
          path: "/agents?tab=exits", icon: "NULL", group_name: "经营与退出", sort: "8", perm: "agent:agent:update", phase: "2", ready: "1",
          module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" },
      );
    },
  },
  {
    // 场地方与拓展 + 运营管理的站点四项 → 网点管理（OM02）。两段叶子按 path 重编号；
    // M_agent 首页改入驻审核（落地页先认 section.href）。
    file: "V149__menu_network_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp149_', id)",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      Object.assign(at("M_venue"), { name: "网点管理", name_en: "Network", name_ar: "إدارة الشبكة", path: "/operation/sites",
        match_paths: '["/venues","/locations","/operation/sites","/operation/overview"]' });
      Object.assign(at("M_operation"), { path: "/operation/fee-plans", match_paths: '["/operation"]' });
      Object.assign(at("M_agent"), { path: "/agents?tab=applies" });
      const plan: [string, string, string, string | null][] = [
        ["/venues?tab=crm", "M_venue", "1", "拓展"],
        ["/venues?tab=onboarding", "M_venue", "2", "拓展"],
        ["/venues?tab=venues", "M_venue", "3", "场地方与合同"],
        ["/venues?tab=contracts", "M_venue", "4", "场地方与合同"],
        ["/operation/sites", "M_venue", "5", "站点与点位"],
        ["/locations?tab=points", "M_venue", "6", "站点与点位"],
        ["/operation/overview", "M_venue", "7", "站点与点位"],
        ["/locations?tab=analysis", "M_venue", "8", "站点与点位"],
        ["/venues?tab=lifecycle", "M_venue", "9", "站点与点位"],
        ["/operation/fee-plans", "M_operation", "1", null],
        ["/operation/fee-adjustments", "M_operation", "2", null],
        ["/operation/app-versions", "M_operation", "3", null],
        ["/operation/banks", "M_operation", "4", null],
        ["/operation/brands", "M_operation", "5", null],
        ["/operation/problems", "M_operation", "6", null],
        ["/operation/notices", "M_operation", "7", null],
        ["/operation/site-sharing", "M_operation", "8", null],
        ["/operation/payee-sharing", "M_operation", "9", null],
      ];
      // 先认出行再改（同迁移的「先打散后赋值」）：边找边改会把刚改好的行当成下一条的候选
      const targets = plan.map(([path]) => rows.find((r) => (r.parent_no === "M_venue" || r.parent_no === "M_operation") && r.path === path)!);
      plan.forEach(([, parent, sort, group], i) => {
        Object.assign(targets[i], { menu_no: `${parent}__${sort}`, parent_no: parent, sort, ...(group ? { group_name: group } : {}) });
      });
    },
  },
  {
    // 设备管理按运营顺序（OM03）：两段叶子按 path 重编号，供应商接入从系统设置搬入，插库存余额与资产差异。
    file: "V150__menu_device_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp150_', id)",
    apply: (rows) => {
      Object.assign(rows.find((r) => r.menu_no === "M_system")!, { path: "/system?tab=payment" });
      const plan: [string, string, string, string | null, Partial<Row>?][] = [
        ["/devices", "M_device", "1", "资产台账"],
        ["/devices?tab=powerbanks", "M_device", "2", "资产台账"],
        ["/devices?tab=codes", "M_device", "3", "资产台账"],
        ["/devices?tab=inventory", "M_device", "5", "库存与流转"],
        ["/system?tab=vendors", "M_device", "7", "设备接入", { path: "/devices?tab=vendors" }],
        ["/devices?tab=monitor", "M_device", "8", "在线运行"],
        ["/devices?tab=commands", "M_device", "9", "在线运行", { name: "远程控制与指令记录" }],
        ["/devices?tab=logs", "M_device", "10", "在线运行"],
        ["/devices?tab=ota", "M_device", "11", "在线运行"],
        ["/system?tab=payment", "M_system", "1", null],
        ["/system?tab=notify", "M_system", "2", null],
        ["/system?tab=notify-log", "M_system", "3", null],
        ["/system?tab=notify-blacklist", "M_system", "4", null],
        ["/system?tab=rules", "M_system", "5", null],
        ["/system?tab=login", "M_system", "6", null],
        ["/system?tab=dict", "M_system", "7", null],
        ["/system?tab=region", "M_system", "8", null],
        ["/system?tab=params", "M_system", "9", null],
        ["/system?tab=tax", "M_system", "10", null],
        ["/system?tab=markets", "M_system", "11", null],
        ["/system?tab=openapi", "M_system", "12", null],
      ];
      const targets = plan.map(([path]) => rows.find((r) => (r.parent_no === "M_device" || r.parent_no === "M_system") && r.path === path)!);
      plan.forEach(([, parent, sort, group, extra], i) => {
        Object.assign(targets[i], { menu_no: `${parent}__${sort}`, parent_no: parent, sort, ...(group ? { group_name: group } : {}), ...(extra ?? {}) });
      });
      const leaf = (no: string, name: string, en: string, ar: string, path: string, sort: string, phase: string, ready: string): Row => ({
        menu_no: no, parent_no: "M_device", name, name_en: en, name_ar: ar, type: "ITEM", path, icon: "NULL",
        group_name: "库存与流转", sort, perm: "device:inventory:read", phase, ready,
        module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
      rows.push(
        leaf("M_device__4", "库存余额", "Stock Balance", "رصيد المخزون", "/devices?tab=stock", "4", "1", "1"),
        leaf("M_device__6", "资产差异", "Asset Discrepancies", "فروقات الأصول", "/devices?tab=asset-diffs", "6", "2", "0"),
      );
    },
  },  {
    // 销售管理（OM04）：订单管理更名，营销管理整段并入后删该 L1，计费 / 会员 / 支付渠道迁入；
    // 五段叶子按 path 重编号（会员两项、支付渠道换到 /orders 的 tab），其后 L1 排序前移一位。
    file: "V151__menu_sales_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp151_', id)",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      Object.assign(at("M_order"), { name: "销售管理", name_en: "Sales", name_ar: "المبيعات",
        match_paths: '["/orders","/marketing","/operation/fee-plans","/operation/fee-adjustments"]' });
      Object.assign(at("M_operation"), { path: "/operation/app-versions" });
      Object.assign(at("M_system"), { path: "/system?tab=notify" });
      const parents = ["M_order", "M_marketing", "M_operation", "M_user", "M_system"];
      const plan: [string[], string, string, string | null][] = [
        [["/operation/fee-plans"], "M_order", "1", "计费"],
        [["/operation/fee-adjustments"], "M_order", "2", "计费"],
        [["/orders"], "M_order", "3", "订单"],
        [["/orders?tab=reservations"], "M_order", "4", "订单"],
        [["/orders?tab=deposit"], "M_order", "5", "订单"],
        [["/orders?tab=free"], "M_order", "6", "订单"],
        [["/system?tab=payment", "/orders?tab=payment-channels"], "M_order", "7", "支付"],
        [["/orders?tab=exceptions"], "M_order", "8", "售后"],
        [["/orders?tab=complaints"], "M_order", "9", "售后"],
        [["/orders?tab=refunds"], "M_order", "10", "售后"],
        [["/marketing"], "M_order", "11", "营销"],
        [["/marketing?tab=campaigns"], "M_order", "12", "营销"],
        [["/marketing?tab=push"], "M_order", "13", "营销"],
        [["/marketing?tab=referral"], "M_order", "14", "营销"],
        [["/users?tab=plans", "/orders?tab=plans"], "M_order", "15", "会员"],
        [["/users?tab=members", "/orders?tab=members"], "M_order", "16", "会员"],
        [["/marketing?tab=ad-slots"], "M_order", "17", "广告"],
        [["/marketing?tab=ad-campaigns"], "M_order", "18", "广告"],
        [["/marketing?tab=ad-delivery"], "M_order", "19", "广告"],
        [["/operation/app-versions"], "M_operation", "1", null],
        [["/operation/banks"], "M_operation", "2", null],
        [["/operation/brands"], "M_operation", "3", null],
        [["/operation/problems"], "M_operation", "4", null],
        [["/operation/notices"], "M_operation", "5", null],
        [["/operation/site-sharing"], "M_operation", "6", null],
        [["/operation/payee-sharing"], "M_operation", "7", null],
        [["/users"], "M_user", "1", null],
        [["/users?tab=logoffs"], "M_user", "2", null],
        [["/users?tab=risk"], "M_user", "3", null],
        [["/users?tab=blacklist"], "M_user", "4", null],
        [["/users?tab=whitelist"], "M_user", "5", null],
        [["/users?tab=wallets"], "M_user", "6", null],
        [["/users?tab=recharge"], "M_user", "7", null],
        [["/users?tab=invoices"], "M_user", "8", null],
        [["/system?tab=notify"], "M_system", "1", null],
        [["/system?tab=notify-log"], "M_system", "2", null],
        [["/system?tab=notify-blacklist"], "M_system", "3", null],
        [["/system?tab=rules"], "M_system", "4", null],
        [["/system?tab=login"], "M_system", "5", null],
        [["/system?tab=dict"], "M_system", "6", null],
        [["/system?tab=region"], "M_system", "7", null],
        [["/system?tab=params"], "M_system", "8", null],
        [["/system?tab=tax"], "M_system", "9", null],
        [["/system?tab=markets"], "M_system", "10", null],
        [["/system?tab=openapi"], "M_system", "11", null],
      ];
      const targets = plan.map(([paths]) => rows.find((r) => parents.includes(r.parent_no) && paths.includes(r.path))!);
      plan.forEach(([paths, parent, sort, group], i) => {
        Object.assign(targets[i], { menu_no: `${parent}__${sort}`, parent_no: parent, sort, path: paths[paths.length - 1],
          ...(group ? { group_name: group } : {}) });
      });
      rows.splice(rows.findIndex((r) => r.menu_no === "M_marketing"), 1);
      for (const [no, sort] of [["M_cs", "14"], ["M_report", "15"], ["M_org", "16"], ["M_system", "17"], ["M_flow", "18"]]) at(no).sort = sort;
    },
  },  {
    // 用户管理（OM05）：用户 → 风险 → 资产；改两项名称；删财务「用户账」组两行（在段末，不影响编号）。
    file: "V152__menu_user_section_by_ops_order.sql",
    mustContain: "DELETE FROM iam_menu WHERE menu_no = 'M_finance__12'",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      for (const no of ["M_user__1", "M_user__2"]) at(no).group_name = "用户";
      for (const no of ["M_user__3", "M_user__4"]) at(no).group_name = "风险";
      Object.assign(at("M_user__5"), { group_name: "风险", name: "免押白名单", name_en: "Deposit-free Whitelist",
        name_ar: "القائمة البيضاء المعفاة من التأمين" });
      for (const no of ["M_user__6", "M_user__8"]) at(no).group_name = "资产";
      Object.assign(at("M_user__7"), { group_name: "资产", name: "充值订单与套餐", name_en: "Top-ups & Packages", name_ar: "الشحن والباقات" });
      for (const no of ["M_finance__11", "M_finance__12"]) rows.splice(rows.findIndex((r) => r.menu_no === no), 1);
    },
  },
  {
    // 运维管理（OM06）：告警管理 + 客服管理并入工单管理（更名、落地页改 /alarms）；客服两个快捷入口删除；
    // 三段叶子按 path 重编号并分三组；删 M_alarm / M_cs 两个 L1，其后 L1 排序前移。
    file: "V153__menu_ops_maintenance_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp153_', id)",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      Object.assign(at("M_workorder"), { name: "运维管理", name_en: "Operations & Maintenance", name_ar: "التشغيل والصيانة",
        path: "/alarms", match_paths: '["/alarms","/cs","/work-orders"]' });
      const parents = ["M_alarm", "M_cs", "M_workorder"];
      for (const path of ["/orders", "/users"]) {
        rows.splice(rows.findIndex((r) => r.parent_no === "M_cs" && r.path === path), 1);
      }
      const plan: [string, string][] = [
        ["/alarms", "告警"], ["/alarms?tab=notices", "告警"], ["/alarms?tab=codes", "告警"], ["/alarms?tab=rules", "告警"],
        ["/cs", "客服"], ["/cs?tab=sessions", "客服"],
        ["/work-orders?view=list", "工单"], ["/work-orders?view=board", "工单"],
        ["/work-orders?view=inspection", "工单"], ["/work-orders?view=sla", "工单"],
      ];
      const targets = plan.map(([path]) => rows.find((r) => parents.includes(r.parent_no) && r.path === path)!);
      plan.forEach(([, group], i) => {
        Object.assign(targets[i], { menu_no: `M_workorder__${i + 1}`, parent_no: "M_workorder", sort: String(i + 1), group_name: group });
      });
      for (const no of ["M_alarm", "M_cs"]) rows.splice(rows.findIndex((r) => r.menu_no === no), 1);
      const l1 = ["M_workorder", "M_venue", "M_agent", "M_order", "M_finance", "M_user", "M_report", "M_org", "M_system", "M_flow"];
      l1.forEach((no, i) => { at(no).sort = String(i + 7); });
    },
  },
  {
    // 结算（OM08）：新插 M_settlement；财务段结算六项（页签换到 /settlement）与运营管理两项分成迁入并分三组；
    // 删「代理分润配置」深链；财务段剩三项重编号；门户三项换路径；结算插在财务之前，其后 L1 排序后移。
    file: "V154__menu_settlement_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp154_', id)",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      rows.push({ menu_no: "M_settlement", parent_no: "NULL", name: "结算", name_en: "Settlement", name_ar: "التسوية",
        type: "MENU", path: "/settlement?tab=settlements", icon: "HandCoins", group_name: "NULL", sort: "11", perm: "NULL",
        phase: "1", ready: "0", module: "finance", modules: "NULL",
        match_paths: '["/settlement","/operation/site-sharing","/operation/payee-sharing"]', pin_bottom: "0", portal_for: "NULL" });
      for (const tab of ["records", "settlements", "withdrawals"]) {
        rows.find((r) => r.parent_no === "M_my-biz" && r.path === `/finance?tab=${tab}`)!.path = `/settlement?tab=${tab}`;
      }
      rows.splice(rows.findIndex((r) => r.parent_no === "M_finance" && r.path === "/agents?tab=commission"), 1);
      const from = (r: Row) => r.parent_no === "M_finance"
        || (r.parent_no === "M_operation" && ["/operation/site-sharing", "/operation/payee-sharing"].includes(r.path));
      const plan: [string, string, string, string | null, string | null][] = [
        ["/finance?tab=rules", "M_settlement", "1", "分润", "/settlement?tab=rules"],
        ["/finance?tab=records", "M_settlement", "2", "分润", "/settlement?tab=records"],
        ["/finance?tab=summary", "M_settlement", "3", "分润", "/settlement?tab=summary"],
        ["/operation/site-sharing", "M_settlement", "4", "分润", null],
        ["/operation/payee-sharing", "M_settlement", "5", "分润", null],
        ["/finance?tab=settlements", "M_settlement", "6", "结算", "/settlement?tab=settlements"],
        ["/finance?tab=withdrawals", "M_settlement", "7", "付款", "/settlement?tab=withdrawals"],
        ["/finance?tab=payout-accounts", "M_settlement", "8", "付款", "/settlement?tab=payout-accounts"],
        ["/finance?tab=ledger", "M_finance", "1", null, null],
        ["/finance?tab=reconcile", "M_finance", "2", null, null],
        ["/finance?tab=invoices", "M_finance", "3", null, null],
      ];
      const targets = plan.map(([path]) => rows.find((r) => from(r) && r.path === path)!);
      plan.forEach(([, parent, sort, group, path], i) => {
        Object.assign(targets[i], { menu_no: `${parent}__${sort}`, parent_no: parent, sort,
          ...(group ? { group_name: group } : {}), ...(path ? { path } : {}) });
      });
      const l1 = ["M_settlement", "M_finance", "M_user", "M_report", "M_org", "M_system", "M_flow"];
      l1.forEach((no, i) => { at(no).sort = String(i + 11); });
    },
  },
  {
    // 财务管理（OM07）：对账 → 账务 → 发票；调整单新插（原是结算单页签里的视图）；落地页改对账。
    file: "V156__menu_finance_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp156_', id) WHERE parent_no = 'M_finance'",
    apply: (rows) => {
      rows.find((r) => r.menu_no === "M_finance")!.path = "/finance?tab=reconcile";
      const byPath = (path: string) => rows.find((r) => r.parent_no === "M_finance" && r.path === path)!;
      const reconcile = byPath("/finance?tab=reconcile");
      const ledger = byPath("/finance?tab=ledger");
      const invoices = byPath("/finance?tab=invoices");
      Object.assign(reconcile, { menu_no: "M_finance__1", sort: "1", group_name: "对账" });
      Object.assign(ledger, { menu_no: "M_finance__2", sort: "2", group_name: "账务" });
      Object.assign(invoices, { menu_no: "M_finance__4", sort: "4", group_name: "发票" });
      rows.push({ menu_no: "M_finance__3", parent_no: "M_finance", name: "调整单", name_en: "Adjustments", name_ar: "قيود التسوية",
        type: "ITEM", path: "/finance?tab=adjustments", icon: "NULL", group_name: "账务", sort: "3",
        perm: "finance:settlement:read", phase: "1", ready: "0", module: "NULL", modules: "NULL", match_paths: "NULL",
        pin_bottom: "0", portal_for: "NULL" });
    },
  },
  {
    // 结算 › 付款：「提现审核」更名「付款任务」（一期提现 = 平台主动付款，D8 / D12）。
    file: "V160__menu_payout_task_rename.sql",
    mustContain: "UPDATE iam_menu SET name = '付款任务'",
    apply: (rows) => {
      Object.assign(rows.find((r) => r.menu_no === "M_settlement__7")!,
        { name: "付款任务", name_en: "Payout Tasks", name_ar: "مهام الدفع" });
    },
  },
  {
    // 设备管理按生命周期重排 + 出库池；商家门户加「我的站点」并认领三条路径。
    file: "V163__menu_device_lifecycle_issue_pool.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp163_', id) WHERE parent_no IN ('M_device', 'M_my-asset')",
    apply: (rows) => {
      const dev: [string, string, string][] = [
        ["/devices", "1", "设备档案"], ["/devices?tab=powerbanks", "2", "设备档案"],
        ["/devices?tab=stock", "3", "仓储与出库"], ["/devices?tab=inventory", "4", "仓储与出库"],
        ["/devices?tab=asset-diffs", "6", "仓储与出库"],
        ["/devices?tab=codes", "7", "绑定上线"], ["/devices?tab=vendors", "8", "绑定上线"],
        ["/devices?tab=monitor", "9", "运行监控"], ["/devices?tab=commands", "10", "运行监控"],
        ["/devices?tab=logs", "11", "运行监控"], ["/devices?tab=ota", "12", "运行监控"],
      ];
      const devRows = dev.map(([path]) => rows.find((r) => r.parent_no === "M_device" && r.path === path)!);
      dev.forEach(([, sort, group], i) => Object.assign(devRows[i], { menu_no: `M_device__${sort}`, sort, group_name: group }));
      Object.assign(rows.find((r) => r.menu_no === "M_device__8")!, { name: "厂商接入", name_en: "Vendor Integration", name_ar: "تكامل المصنّعين" });
      const mine = (path: string) => rows.find((r) => r.parent_no === "M_my-asset" && r.path === path)!;
      const d = mine("/devices"); const o = mine("/orders");
      Object.assign(d, { menu_no: "M_my-asset__2", sort: "2" });
      Object.assign(o, { menu_no: "M_my-asset__3", sort: "3" });
      rows.find((r) => r.menu_no === "M_my-asset")!.match_paths = '["/devices","/operation/sites","/orders"]';
      rows.push(
        { menu_no: "M_device__5", parent_no: "M_device", name: "出库池", name_en: "Outbound Pool", name_ar: "مجمع الإخراج",
          type: "ITEM", path: "/devices?tab=issued", icon: "NULL", group_name: "仓储与出库", sort: "5",
          perm: "device:inventory:read", phase: "1", ready: "0", module: "NULL", modules: "NULL", match_paths: "NULL",
          pin_bottom: "0", portal_for: "NULL" },
        { menu_no: "M_my-asset__1", parent_no: "M_my-asset", name: "我的站点", name_en: "My Sites", name_ar: "مواقعي",
          type: "ITEM", path: "/operation/sites", icon: "NULL", group_name: "设备与订单", sort: "1",
          perm: "location:poi:read", phase: "1", ready: "0", module: "NULL", modules: "NULL", match_paths: "NULL",
          pin_bottom: "0", portal_for: "NULL" },
      );
    },
  },
  {
    // 设备管理补五个生命周期视图：设备段按 path 重编号，插五条新叶。
    file: "V165__menu_device_lifecycle_views.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp165_', id) WHERE parent_no = 'M_device'",
    apply: (rows) => {
      const plan: [string, string, string][] = [
        ["/devices", "1", "设备档案"], ["/devices?tab=powerbanks", "2", "设备档案"],
        ["/devices?tab=stock", "3", "仓储与出库"], ["/devices?tab=inventory", "4", "仓储与出库"],
        ["/devices?tab=issued", "5", "仓储与出库"], ["/devices?tab=asset-diffs", "6", "仓储与出库"],
        ["/devices?tab=codes", "9", "绑定上线"], ["/devices?tab=vendors", "10", "绑定上线"],
        ["/devices?tab=monitor", "11", "运行监控"], ["/devices?tab=commands", "12", "运行监控"],
        ["/devices?tab=logs", "13", "运行监控"], ["/devices?tab=ota", "14", "运行监控"],
      ];
      const targets = plan.map(([path]) => rows.find((r) => r.parent_no === "M_device" && r.path === path)!);
      plan.forEach(([, sort, group], i) => Object.assign(targets[i], { menu_no: `M_device__${sort}`, sort, group_name: group }));
      const leaf = (sort: string, name: string, en: string, ar: string, tab: string, group: string, perm: string) => ({
        menu_no: `M_device__${sort}`, parent_no: "M_device", name, name_en: en, name_ar: ar, type: "ITEM",
        path: `/devices?tab=${tab}`, icon: "NULL", group_name: group, sort, perm, phase: "1", ready: "0",
        module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
      rows.push(
        leaf("7", "部署与装机", "Installations", "عمليات التركيب", "installs", "部署与安装", "workorder:wo:read"),
        leaf("8", "待上线", "Pending Go-live", "بانتظار التشغيل", "pending-live", "绑定上线", "device:cabinet:read"),
        leaf("15", "故障与维修", "Faults & Repairs", "الأعطال والإصلاح", "repairs", "维护与退出", "device:cabinet:read"),
        leaf("16", "撤机与回仓", "Removals & Returns", "الإزالة والإرجاع", "removals", "维护与退出", "workorder:wo:read"),
        leaf("17", "报废与遗失", "Scrapped & Lost", "المُتلَف والمفقود", "retired", "维护与退出", "device:cabinet:read"),
      );
    },
  },
  {
    // 商家门户「申请提现」→「提现记录」，改挂 :read（一期提现 = 平台付款任务，商家只看记录，D8）。
    file: "V166__menu_my_withdrawals_read_only.sql",
    mustContain: "UPDATE iam_menu SET name = '提现记录'",
    apply: (rows) => {
      Object.assign(rows.find((r) => r.menu_no === "M_my-biz__4")!,
        { name: "提现记录", name_en: "Withdrawal Records", name_ar: "سجل السحوبات", perm: "finance:withdrawal:read" });
    },
  },
  {
    // 商家门户「我的经营」加叶子「商家资料」（agent:profile:read，只看自己）。
    file: "V175__merchant_profile.sql",
    mustContain: "'M_my-biz__5' AS menu_no",
    apply: (rows) => {
      rows.push({
        menu_no: "M_my-biz__5", parent_no: "M_my-biz", name: "商家资料", name_en: "Merchant Profile", name_ar: "ملف التاجر",
        type: "ITEM", path: "/profile", icon: "NULL", group_name: "我的商家", sort: "5", perm: "agent:profile:read",
        phase: "1", ready: "0", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 商家管理 ›「设备/点位划拨」更名「整站换商家」（V169 留空，见迁移说明）。
    file: "V171__menu_assign_site_owner.sql",
    mustContain: "UPDATE iam_menu SET name = '整站换商家'",
    apply: (rows) => {
      Object.assign(rows.find((r) => r.parent_no === "M_agent" && r.path === "/agents?tab=assign")!,
        { name: "整站换商家", name_en: "Site Owner Change", name_ar: "نقل الموقع إلى تاجر" });
    },
  },
  {
    // 系统管理（OM11）：员工与权限 + 系统设置 + 运营管理基础资料 + 流程配置与统计，五组；删 M_org / M_operation，其后 L1 前移。
    file: "V172__menu_system_section_by_ops_order.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp172_', id) WHERE parent_no IN ('M_org', 'M_system', 'M_operation', 'M_flow')",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      Object.assign(at("M_system"), { name: "系统管理", name_en: "System", name_ar: "إدارة النظام", path: "/employees?tab=employees",
        match_paths: '["/system","/employees","/operation","/flow/processes","/flow/settings","/flow/access","/flow/instances","/flow/stats"]' });
      const parents = ["M_org", "M_system", "M_operation", "M_flow"];
      const plan: [string, string][] = [
        ["/employees?tab=employees", "员工与权限"], ["/employees?tab=org", "员工与权限"], ["/employees?tab=roles", "员工与权限"],
        ["/employees?tab=menus", "员工与权限"], ["/employees?tab=audit", "员工与权限"], ["/employees?tab=performance", "员工与权限"],
        ["/system?tab=rules", "规则与参数"], ["/system?tab=login", "规则与参数"], ["/system?tab=params", "规则与参数"],
        ["/system?tab=tax", "规则与参数"], ["/system?tab=markets", "规则与参数"], ["/system?tab=openapi", "规则与参数"],
        ["/system?tab=dict", "基础资料"], ["/system?tab=region", "基础资料"],
        ["/operation/banks", "基础资料"], ["/operation/brands", "基础资料"], ["/operation/problems", "基础资料"],
        ["/operation/app-versions", "基础资料"], ["/operation/notices", "基础资料"],
        ["/system?tab=notify", "消息触达"], ["/system?tab=notify-log", "消息触达"], ["/system?tab=notify-blacklist", "消息触达"],
        ["/flow/processes", "流程"], ["/flow/settings", "流程"], ["/flow/access", "流程"], ["/flow/instances", "流程"], ["/flow/stats", "流程"],
      ];
      const flow = ["/flow/todo", "/flow/approvals", "/flow/queue", "/flow/tickets"];
      const find = (path: string) => rows.find((r) => parents.includes(r.parent_no) && r.path === path)!;
      const sysRows = plan.map(([path]) => find(path));
      const flowRows = flow.map(find);
      sysRows.forEach((r, i) => Object.assign(r, { menu_no: `M_system__${i + 1}`, parent_no: "M_system", sort: String(i + 1), group_name: plan[i][1] }));
      flowRows.forEach((r, i) => Object.assign(r, { menu_no: `M_flow__${i + 1}`, parent_no: "M_flow", sort: String(i + 1) }));
      for (const no of ["M_org", "M_operation"]) rows.splice(rows.findIndex((r) => r.menu_no === no), 1);
      ["M_device", "M_workorder", "M_venue", "M_agent", "M_order", "M_settlement", "M_finance", "M_user", "M_report", "M_system", "M_flow"]
        .forEach((no, i) => { at(no).sort = String(i + 5); });
    },
  },
  {
    // 合并 L1 后补回模块前缀：营销 / 计费并入销售管理、营销（充值）并入用户管理、组织并入系统管理。
    file: "V176__menu_section_modules_after_merge.sql",
    mustContain: "UPDATE iam_menu SET modules = '[\"system\",\"org\"]'",
    apply: (rows) => {
      const at = (no: string) => rows.find((r) => r.menu_no === no)!;
      at("M_order").modules = '["order","marketing","pricing"]';
      at("M_user").modules = '["user","marketing"]';
      at("M_system").modules = '["system","org"]';
    },
  },
  {
    // 仓储与出库加「仓库」（只读，S0 接口），排在库存余额前；设备叶子重编为 1 ~ 18。
    file: "V190__menu_device_warehouses.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp190_', id) WHERE parent_no = 'M_device'",
    apply: (rows) => {
      const order = ["/devices", "/devices?tab=powerbanks", null, "/devices?tab=stock", "/devices?tab=inventory",
        "/devices?tab=issued", "/devices?tab=asset-diffs", "/devices?tab=installs", "/devices?tab=pending-live",
        "/devices?tab=codes", "/devices?tab=vendors", "/devices?tab=monitor", "/devices?tab=commands",
        "/devices?tab=logs", "/devices?tab=ota", "/devices?tab=repairs", "/devices?tab=removals", "/devices?tab=retired"];
      const targets = order.map((path) => (path ? rows.find((r) => r.parent_no === "M_device" && r.path === path)! : null));
      targets.forEach((r, i) => { if (r) Object.assign(r, { menu_no: `M_device__${i + 1}`, sort: String(i + 1) }); });
      rows.push({
        menu_no: "M_device__3", parent_no: "M_device", name: "仓库", name_en: "Warehouses", name_ar: "المستودعات", type: "ITEM",
        path: "/devices?tab=warehouses", icon: "NULL", group_name: "仓储与出库", sort: "3", perm: "device:inventory:read",
        phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 新增「入库与质检」组：到仓待检取 sort 3，原 3 ~ 18 顺延为 4 ~ 19。
    file: "V193__menu_device_intakes.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp193_', id) WHERE parent_no = 'M_device' AND sort >= 3",
    apply: (rows) => {
      rows.filter((r) => r.parent_no === "M_device" && Number(r.sort) >= 3).forEach((r) => {
        const sort = String(Number(r.sort) + 1);
        Object.assign(r, { sort, menu_no: `M_device__${sort}` });
      });
      rows.push({
        menu_no: "M_device__3", parent_no: "M_device", name: "到仓待检", name_en: "Intake Inspection", name_ar: "فحص الاستلام", type: "ITEM",
        path: "/devices?tab=intakes", icon: "NULL", group_name: "入库与质检", sort: "3", perm: "device:inventory:read",
        phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // S4：仓储与出库加「库存流水」「盘点」（排在库存余额之后，sort 6 / 7）；设备叶子整体按 sort 重编号。
    file: "V199__stock_ledger_count_menu.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp199_', id) WHERE parent_no = 'M_device'",
    apply: (rows) => {
      const dev = rows.filter((r) => r.parent_no === "M_device");
      dev.filter((r) => Number(r.sort) >= 6).forEach((r) => { r.sort = String(Number(r.sort) + 2); });
      dev.forEach((r) => { r.menu_no = `M_device__${r.sort}`; });
      for (const [sort, name, en, ar, tab] of [
        ["6", "库存流水", "Stock Ledger", "سجل المخزون", "ledger"],
        ["7", "盘点", "Stock Count", "الجرد", "counts"],
      ]) {
        rows.push({
          menu_no: `M_device__${sort}`, parent_no: "M_device", name, name_en: en, name_ar: ar, type: "ITEM",
          path: `/devices?tab=${tab}`, icon: "NULL", group_name: "仓储与出库", sort, perm: "device:inventory:read",
          phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
        });
      }
    },
  },
  {
    // 设备管理一期收齐：五个叶子改 L1 + 就绪。
    file: "V200__menu_device_phase1_ready.sql",
    mustContain: "UPDATE iam_menu SET phase = 1, ready = 1",
    apply: (rows) => {
      const paths = ["/devices?tab=inventory", "/devices?tab=asset-diffs", "/devices?tab=codes", "/devices?tab=logs", "/devices?tab=ota"];
      rows.filter((r) => r.parent_no === "M_device" && paths.includes(r.path)).forEach((r) => Object.assign(r, { phase: "1", ready: "1" }));
    },
  },
  {
    // 入库与质检加「供应商」（sort 4，排在到仓待检后）；原 4 起顺延，设备叶子按 sort 重编号。
    file: "V201__inventory_supplier_warehouse_admin.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp201_', id) WHERE parent_no = 'M_device'",
    apply: (rows) => {
      const dev = rows.filter((r) => r.parent_no === "M_device");
      dev.filter((r) => Number(r.sort) >= 4).forEach((r) => { r.sort = String(Number(r.sort) + 1); });
      dev.forEach((r) => { r.menu_no = `M_device__${r.sort}`; });
      rows.push({
        menu_no: "M_device__4", parent_no: "M_device", name: "供应商", name_en: "Suppliers", name_ar: "الموردون", type: "ITEM",
        path: "/devices?tab=suppliers", icon: "NULL", group_name: "入库与质检", sort: "4", perm: "device:inventory:read",
        phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 仓储与出库加「处置出库」（sort 11，排在出库池后）；原 11 起顺延，设备叶子按 sort 重编号。
    file: "V203__inventory_disposal.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp203_', id) WHERE parent_no = 'M_device'",
    apply: (rows) => {
      const dev = rows.filter((r) => r.parent_no === "M_device");
      dev.filter((r) => Number(r.sort) >= 11).forEach((r) => { r.sort = String(Number(r.sort) + 1); });
      dev.forEach((r) => { r.menu_no = `M_device__${r.sort}`; });
      rows.push({
        menu_no: "M_device__11", parent_no: "M_device", name: "处置出库", name_en: "Disposals", name_ar: "الإخراج والإتلاف", type: "ITEM",
        path: "/devices?tab=disposals", icon: "NULL", group_name: "仓储与出库", sort: "11", perm: "device:inventory:read",
        phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 数据报表加「库存报表」（sort 4，排在财务报表后）；原 4 起顺延，报表叶子按 sort 重编号。
    file: "V206__report_inventory.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp206_', id) WHERE parent_no = 'M_report'",
    apply: (rows) => {
      const rep = rows.filter((r) => r.parent_no === "M_report");
      rep.filter((r) => Number(r.sort) >= 4).forEach((r) => { r.sort = String(Number(r.sort) + 1); });
      rep.forEach((r) => { r.menu_no = `M_report__${r.sort}`; });
      rows.push({
        menu_no: "M_report__4", parent_no: "M_report", name: "库存报表", name_en: "Inventory Report", name_ar: "تقرير المخزون", type: "ITEM",
        path: "/reports?tab=inventory", icon: "NULL", group_name: "NULL", sort: "4", perm: "report:inventory:read",
        phase: "1", ready: "1", module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL",
      });
    },
  },
  {
    // 从设备管理拆出库存管理（L1，插在设备管理之后，其后 L1 顺延）：库存叶子换段、换 path、换分组；
    // 到仓待检更名入库单；库存调拨页签键 inventory → transfers；新增物料档案 / 物料单据 / 物料库存（未就绪）与库存设置。
    file: "V209__menu_inventory_section.sql",
    mustContain: "UPDATE iam_menu SET menu_no = CONCAT('M_tmp209_', id) WHERE parent_no IN ('M_device', 'M_inventory')",
    apply: (rows) => {
      rows.filter((r) => r.parent_no === "NULL" && Number(r.sort) >= 6).forEach((r) => { r.sort = String(Number(r.sort) + 1); });
      rows.push({ menu_no: "M_inventory", parent_no: "NULL", name: "库存管理", name_en: "Inventory", name_ar: "المخزون",
        type: "MENU", path: "/inventory?tab=stock", icon: "Warehouse", group_name: "NULL", sort: "6", perm: "NULL",
        phase: "1", ready: "0", module: "device", modules: "NULL", match_paths: '["/inventory"]', pin_bottom: "0", portal_for: "NULL" });
      const inv: [string, string, string, string][] = [
        ["/devices?tab=warehouses", "warehouses", "2", "主数据"],
        ["/devices?tab=suppliers", "suppliers", "3", "主数据"],
        ["/devices?tab=intakes", "intakes", "4", "出入库"],
        ["/devices?tab=issued", "issued", "6", "出入库"],
        ["/devices?tab=disposals", "disposals", "7", "出入库"],
        ["/devices?tab=inventory", "transfers", "8", "库内作业"],
        ["/devices?tab=counts", "counts", "9", "库内作业"],
        ["/devices?tab=asset-diffs", "asset-diffs", "10", "库内作业"],
        ["/devices?tab=stock", "stock", "11", "库存账"],
        ["/devices?tab=ledger", "ledger", "13", "库存账"],
      ];
      for (const [from, tab, sort, group] of inv) {
        const r = rows.find((x) => x.parent_no === "M_device" && x.path === from)!;
        Object.assign(r, { menu_no: `M_inventory__${sort}`, parent_no: "M_inventory", sort, group_name: group, path: `/inventory?tab=${tab}` });
        if (tab === "intakes") Object.assign(r, { name: "入库单", name_en: "Receipts", name_ar: "إيصالات الاستلام" });
      }
      const dev = ["/devices", "/devices?tab=powerbanks", "/devices?tab=installs", "/devices?tab=pending-live", "/devices?tab=codes",
        "/devices?tab=vendors", "/devices?tab=monitor", "/devices?tab=commands", "/devices?tab=logs", "/devices?tab=ota",
        "/devices?tab=repairs", "/devices?tab=removals", "/devices?tab=retired"];
      dev.forEach((path, i) => Object.assign(rows.find((x) => x.parent_no === "M_device" && x.path === path)!,
        { menu_no: `M_device__${i + 1}`, sort: String(i + 1) }));
      const leaf = (sort: string, name: string, en: string, ar: string, tab: string, group: string, ready: string): Row => ({
        menu_no: `M_inventory__${sort}`, parent_no: "M_inventory", name, name_en: en, name_ar: ar, type: "ITEM",
        path: `/inventory?tab=${tab}`, icon: "NULL", group_name: group, sort, perm: "device:inventory:read",
        phase: "1", ready, module: "NULL", modules: "NULL", match_paths: "NULL", pin_bottom: "0", portal_for: "NULL" });
      rows.push(leaf("1", "物料档案", "Materials", "المواد", "materials", "主数据", "0"));
      rows.push(leaf("5", "物料单据", "Material Documents", "مستندات المواد", "material-docs", "出入库", "0"));
      rows.push(leaf("12", "物料库存", "Material Stock", "مخزون المواد", "material-stock", "库存账", "0"));
      rows.push(leaf("14", "库存设置", "Inventory Settings", "إعدادات المخزون", "settings", "设置", "1"));
    },
  },
];

const COLS = [
  "menu_no", "parent_no", "name", "name_en", "name_ar", "type", "path", "icon", "group_name", "sort",
  "perm", "phase", "ready", "module", "modules", "match_paths", "pin_bottom", "portal_for",
];

/**
 * 解析生成出来的 VALUES 行。**只解析自己生成的格式**，不是通用 SQL 解析器 ——
 * 每个值要么是 NULL、要么是整数、要么是单引号串（内部 '' 转义）。
 */
function parseRows(sql: string): Row[] {
  const out: Row[] = [];
  for (const line of sql.split("\n")) {
    const t = line.trim();
    if (!t.startsWith("(")) continue;
    const body = t.replace(/^\(/, "").replace(/\),?;?$/, "");
    const vals: string[] = [];
    let i = 0;
    while (i < body.length) {
      while (body[i] === " " || body[i] === ",") i++;
      if (i >= body.length) break;
      if (body[i] === "'") {
        let v = "";
        i++;
        while (i < body.length) {
          if (body[i] === "'" && body[i + 1] === "'") { v += "'"; i += 2; continue; }
          if (body[i] === "'") { i++; break; }
          v += body[i++];
        }
        vals.push(v);
      } else {
        let v = "";
        while (i < body.length && body[i] !== ",") v += body[i++];
        vals.push(v.trim());
      }
    }
    expect(vals.length, `这一行的列数对不上：${t.slice(0, 60)}…`).toBe(COLS.length);
    out.push(Object.fromEntries(COLS.map((c, k) => [c, vals[k]])));
  }
  return out;
}

/** nav.ts → 与迁移同形的行，用于逐字段比对。 */
function rowsFromNav(): Row[] {
  const j = (v?: string[]) => (v && v.length ? JSON.stringify(v) : "NULL");
  const out: Row[] = [];
  NAV.forEach((s, si) => {
    out.push({
      menu_no: `M_${s.key}`, parent_no: "NULL", name: s.label,
      name_en: tNav(s.label, "en"), name_ar: tNav(s.label, "ar"),
      // DIR=目录、MENU=可点页面，对齐冻结契约；没有叶子的 section 自己就是一页
      // 两档：MENU（分组）/ ITEM（叶子），词表由 V68 钉在列注释上
      type: "MENU",
      path: s.href, icon: s.icon ?? "NULL", group_name: "NULL", sort: String(si + 1),
      perm: s.perm ?? "NULL", phase: String(s.phase ?? 1), ready: "0",
      module: s.module ?? "NULL", modules: j(s.modules), match_paths: j(s.match),
      pin_bottom: s.pinBottom ? "1" : "0", portal_for: j(s.portalFor as string[] | undefined),
    });
    (s.children ?? []).forEach((c, li) => {
      out.push({
        menu_no: `M_${s.key}__${li + 1}`, parent_no: `M_${s.key}`, name: c.label,
        name_en: tNav(c.label, "en"), name_ar: tNav(c.label, "ar"), type: "ITEM",
        path: c.href, icon: "NULL", group_name: c.group ?? "NULL", sort: String(li + 1),
        perm: c.perm ?? "NULL", phase: String(c.phase ?? 1), ready: c.ready ? "1" : "0",
        module: "NULL", modules: "NULL", match_paths: "NULL",
        pin_bottom: "0", portal_for: "NULL",
      });
    });
  });
  return out;
}

describe("iam_menu 的种子必须与 nav.ts 逐节点相等", () => {
  const sql = readFileSync(MIGRATION, "utf-8");
  const fromSql = parseRows(sql);
  for (const p of PATCHES) {
    expect(readFileSync(MIGRATION_DIR + p.file, "utf-8"), `${p.file} 里找不到补丁语句`).toContain(p.mustContain);
    p.apply(fromSql);
  }
  const fromNav = rowsFromNav();

  it("节点数一致——少一行就是菜单里少一项，而那不会报错", () => {
    expect(fromSql.length).toBe(fromNav.length);
  });

  it("逐行逐字段一致", () => {
    // 先按 menu_no 对齐再比，否则顺序差一位会报出 127 条假差异，真正差的那条反而埋了
    const bySql = new Map(fromSql.map((r) => [r.menu_no, r]));
    const diffs: string[] = [];
    for (const want of fromNav) {
      const got = bySql.get(want.menu_no);
      if (!got) { diffs.push(`${want.menu_no}（"${want.name}"）在迁移里没有`); continue; }
      for (const c of COLS) {
        if (got[c] !== want[c]) diffs.push(`${want.menu_no}.${c}: 迁移=${got[c]} / nav.ts=${want[c]}`);
      }
    }
    for (const got of fromSql) {
      if (!fromNav.some((w) => w.menu_no === got.menu_no)) {
        diffs.push(`${got.menu_no}（"${got.name}"）只在迁移里有，nav.ts 没有`);
      }
    }
    expect(diffs, "nav.ts 改了而迁移没跟上——重新生成，别手改迁移").toEqual([]);
  });
});
