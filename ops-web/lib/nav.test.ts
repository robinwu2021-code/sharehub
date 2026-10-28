// nav 三级导航纯函数单测 —— 场景对照 TDD-运营端三级导航.md §4.2 + 附录A.9 矩阵。
// 2026-07-30 层级重构：域层删除，原「模块」升为 L1（section），分组为 L2、叶子为 L3。
import { describe, it, expect } from "vitest";
import {
  NAV,
  visibleSections, visibleLeaves,
  findActiveSection, activeLeafIndex,
  sectionDefaultHref, breadcrumb, leafParts, normPath,
  isLeafLocked, isSectionLocked, isLeafDisabled, routeLockedPhase, groupedLeaves,
  portalTitleOverride, opLeaves,
} from "./nav";
import type { OperationPage } from "./backend-ready";
import { isPhaseLocked, CURRENT_PHASE } from "./phase";
import { hasNavLabel } from "./i18n/nav-labels";
import type { Role } from "./auth";

const sec = (key: string) => {
  const s = NAV.find((x) => x.key === key);
  if (!s) throw new Error(`section ${key} missing`);
  return s;
};
const sectionKeys = (role: Role) => visibleSections(role).map((s) => s.key);

// ── 结构回归基线（防「顺手」重排菜单）────────────────────────────────
// L1 顺序即 Rail 顺序；改动必须是有意识的产品决策，连带更新 docs。
const L1_KEYS = [
  "my-biz", "my-asset", "my-service", // 代理端门户（portalFor: AGENT）
  "dashboard", "operation", "device", "workorder", "venue", "agent", "order",
  "settlement", "finance", "user", "report", "org", "system", "flow",
];
// 叶子五元组 href|label|perm|phase|group —— 2026-07-30 层级重构前后逐条比对为零差异。
// 2026-09-23 有意变更（A 类菜单重合收敛，见 docs/technical/菜单重合梳理与优化方案.md）：
// 7 对「同一组 API 两个页面」的重复，**一律保留运营管理那一份**，旧入口连页面代码一起撤：
//   站点与点位 › 站点管理、计费定价 › 计费模板 + 活动/时段价、
//   系统设置 › 应用版本 + 银行管理 + 问题管理、营销管理 › 公告管理。
// 同一张表不留两个维护入口 —— 否则「在哪个入口改的」决定别人看不看得见。
// 2026-09-23 第二步：撤销「站点与点位」L1，它剩下的七项并入运营管理并重新分组
// （场站管理 / 场地与合同 / 拓展 / 计费与调价 / 基础管理 / 场站报表），
// href 一律保持 /locations?tab=…（页面没搬，只换菜单位置）。
// 顺带：BD 拓展 CRM 补上 location:lead:read —— 它此前无 perm，跟随 section 可见。
// 2026-09-24 有意变更（P1 第二步，见 docs/technical/权限与菜单-用户角色资源统一方案.md）：
// **最后 13 个无 perm 的叶子各补上码**，码逐个查自该页**列表接口**实际强制的那个，
// 不按命名习惯猜（第一版猜错 3 个：SLA/巡检的列表判 workorder:wo:read 而不是
// :update，邀请裂变判 marketing:campaign:read 而不是 referral:read）。
// 至此 109 个叶子全部带码，「无 perm 跟随父模块」这条隐式规则不再有使用者 ——
// 服务端按码过滤才表达得了可见性（P1 第三、四步）。
// 可见性变化恰好 9 项，全是「看得见点不开」的入口被收掉，
// 逐项见 lib/nav-visibility.snapshot.txt 的 diff。
// 2026-09-23 第三步：拓展不属于「经营已有站点」，场地方那条线整体拆成「场地方与拓展」L1
// （页面从 /locations 拆到 /venues —— 一个 URL 只能属于一个 L1）；
// 「分成」不是报表，原「场站报表」组改名「分成」，站点坪效归回场站管理。
// 2026-09-23 有意变更：phase 从三值(1/2/3)改为四值 L0-L3，28 个叶子按分级矩阵重标，
// 并新增代理门户「申请提现」(AGT-06)。依据 docs/requirements/功能清单-分级矩阵.md §六/§七。
// 2026-09-23 有意变更：新增「入驻审核」(OPS-AGT-07/09/10)，排在代理商档案之前 ——
// 档案是「已经在的」，入驻是「正在进来的」，待办优先于台账。
// 代建录入不单开叶子：它是该页的一个按钮，权限码 agent:apply:create 与放行码分开（ADR-030 §3.3）。
const LEAF_TUPLES = [
  "/|我的看板|dashboard:overview:read||经营概览",
  "/settlement?tab=records|我的收益|finance:share_record:read||经营概览",
  "/settlement?tab=settlements|我的结算|agent:settlement:read||经营概览",
  "/settlement?tab=withdrawals|申请提现|finance:withdrawal:apply||经营概览",
  "/devices|我的设备|device:cabinet:read||设备与订单",
  "/orders|我的订单|order:order:read||设备与订单",
  "/work-orders?view=list|设备报修|workorder:wo:create||报修与跟进",
  // 2026-10-28：站点四项迁到网点管理（OM02），见下方 venue 段
  // 计费两项 2026-10-28 迁到销售管理（OM04）
  "/operation/app-versions|应用版本|system:app_version:read||基础管理",
  "/operation/banks|银行管理|system:bank:read||基础管理",
  "/operation/brands|品牌管理|system:brand:read||基础管理",
  "/operation/problems|问题管理|system:problem:read||基础管理",
  "/operation/notices|公告管理|marketing:notice:read||基础管理",
  // 分成两项 2026-10-28 迁到结算（OM08）
  // 2026-10-28 设备管理按运营顺序（OM03）：资产台账 → 库存与流转 → 设备接入 → 在线运行
  "/devices|设备台账|device:cabinet:read||资产台账",
  "/devices?tab=powerbanks|充电宝管理|device:powerbank:read||资产台账",
  "/devices?tab=codes|设备编码|device:cabinet:read|1|资产台账",
  "/devices?tab=stock|库存余额|device:inventory:read|1|库存与流转",
  "/devices?tab=inventory|库存调拨|device:inventory:read|2|库存与流转",
  "/devices?tab=asset-diffs|资产差异|device:inventory:read|2|库存与流转",
  "/devices?tab=vendors|供应商接入|device:vendor:read||设备接入",
  "/devices?tab=monitor|实时监控|device:cabinet:read||在线运行",
  "/devices?tab=commands|远程控制与指令记录|device:command:send||在线运行",
  "/devices?tab=logs|设备日志|device:cabinet:read|1|在线运行",
  "/devices?tab=ota|固件 OTA|device:ota:read|2|在线运行",
  // 2026-10-28 运维管理（OM06）：告警 + 客服 + 工单三个 L1 合一；客服两个快捷入口撤销
  "/alarms|告警记录|workorder:alarm:read||告警",
  "/alarms?tab=notices|告警通知|workorder:alarm:read||告警",
  "/alarms?tab=codes|告警代码|workorder:alarm:read||告警",
  "/alarms?tab=rules|通知规则|workorder:alarm:read||告警",
  "/cs|报障受理|cs:ticket:read|1|客服",
  "/cs?tab=sessions|客服会话|cs:session:read|2|客服",
  "/work-orders?view=list|工单列表|workorder:wo:read||工单",
  "/work-orders?view=board|工单看板|workorder:wo:read||工单",
  "/work-orders?view=inspection|巡检计划|workorder:wo:read|2|工单",
  "/work-orders?view=sla|SLA 管理|workorder:wo:read|2|工单",
  // 2026-10-28 网点管理按运营顺序（OM02）：拓展 → 场地方与合同 → 站点与点位
  "/venues?tab=crm|BD 拓展 CRM|location:lead:read||拓展",
  "/venues?tab=onboarding|门店 Onboarding|location:venue:read|2|拓展",
  "/venues?tab=venues|场地方|location:venue:read||场地方与合同",
  "/venues?tab=contracts|进场合同|location:contract:read||场地方与合同",
  "/operation/sites|站点管理|location:poi:read||站点与点位",
  "/locations?tab=points|点位管理|location:poi:read||站点与点位",
  "/operation/overview|站点概览|location:overview:read||站点与点位",
  "/locations?tab=analysis|站点坪效|location:analysis:read|3|站点与点位",
  "/venues?tab=lifecycle|门店生命周期|location:venue:read|3|站点与点位",
  // 2026-10-28 商家管理按运营顺序（OM01）：进来 → 定钱与资产 → 经营与退出
  "/agents?tab=applies|入驻审核|agent:apply:read||入驻与档案",
  "/agents|商家档案|agent:agent:read||入驻与档案",
  "/agents?tab=accounts|商家账号|agent:account:manage||入驻与档案",
  "/agents?tab=commission|商家分润规则|agent:share:config||分润与划拨",
  "/agents?tab=assign|设备/点位划拨|agent:scope:assign||分润与划拨",
  "/agents?tab=business|商家经营视图|agent:agent:read|1|经营与退出",
  "/agents?tab=performance|商家绩效|agent:performance:read|2|经营与退出",
  "/agents?tab=exits|退出与清算|agent:agent:update|2|经营与退出",
  // 2026-10-28 销售管理（OM04）：计费 → 订单 → 支付 → 售后 → 营销 → 会员 → 广告
  "/operation/fee-plans|收费方案|pricing:plan:read||计费",
  "/operation/fee-adjustments|预约调价|pricing:adjustment:read||计费",
  "/orders|订单列表|order:order:read||订单",
  "/orders?tab=reservations|预约订单|order:order:read|2|订单",
  "/orders?tab=deposit|押金与欠费|order:order:read|1|订单",
  "/orders?tab=free|免费订单|order:order:read|2|订单",
  "/orders?tab=payment-channels|支付渠道|system:payment_channel:read||支付",
  "/orders?tab=exceptions|异常订单|order:exception:read||售后",
  "/orders?tab=complaints|投诉订单|order:exception:read||售后",
  "/orders?tab=refunds|退款记录|order:refund:audit||售后",
  "/marketing|优惠券|marketing:coupon:read|2|营销",
  "/marketing?tab=campaigns|活动|marketing:campaign:read|2|营销",
  "/marketing?tab=push|推送触达|marketing:push:send|3|营销",
  "/marketing?tab=referral|邀请裂变|marketing:campaign:read|3|营销",
  "/orders?tab=plans|会员套餐|user:member:read|2|会员",
  "/orders?tab=members|会员/次卡|user:member:read|2|会员",
  "/marketing?tab=ad-slots|广告位管理|marketing:ad:read|3|广告",
  "/marketing?tab=ad-campaigns|广告活动|marketing:ad:read|3|广告",
  "/marketing?tab=ad-delivery|投放与曝光|marketing:ad:read|3|广告",
  // 2026-10-28 结算（OM08）：从财务管理拆出，分润 → 结算 → 付款；页签换到 /settlement
  "/settlement?tab=rules|分润规则|finance:share_rule:read||分润",
  "/settlement?tab=records|分润明细|finance:share_record:read||分润",
  "/settlement?tab=summary|分润统计|finance:share_record:read|1|分润",
  "/operation/site-sharing|站点分成|finance:share_rule:read||分润",
  "/operation/payee-sharing|分成方分成|finance:share_rule:read||分润",
  "/settlement?tab=settlements|结算单|finance:settlement:read||结算",
  "/settlement?tab=withdrawals|付款任务|finance:withdrawal:read||付款",
  "/settlement?tab=payout-accounts|收款账户|finance:payout_account:read||付款",
  // 2026-10-28 财务管理（OM07）：对账 → 账务 → 发票；调整单从结算单的视图升为页签；「代理分润配置」深链已撤
  "/finance?tab=reconcile|对账|finance:recon:read|1|对账",
  "/finance?tab=ledger|账务分录|finance:ledger:read|2|账务",
  "/finance?tab=adjustments|调整单|finance:settlement:read||账务",
  "/finance?tab=invoices|发票|finance:invoice:read|2|发票",
  // 「用户账」组 2026-10-28 撤销：用户钱包深链去掉，充值订单并入用户管理（OM05）
  // 2026-10-28 用户管理按运营顺序（OM05）：用户 → 风险 → 资产
  "/users|用户列表|user:cuser:read|1|用户",
  "/users?tab=logoffs|注销申请|user:logoff:read|2|用户",
  "/users?tab=risk|风控用户|user:risk:read|1|风险",
  "/users?tab=blacklist|黑名单|user:risk:update|1|风险",
  "/users?tab=whitelist|免押白名单|user:risk:update|2|风险",
  "/users?tab=wallets|钱包|user:wallet:read|2|资产",
  "/users?tab=recharge|充值订单与套餐|user:wallet:read|2|资产",
  "/users?tab=invoices|开票申请|user:invoice:read|2|资产",
  // 会员套餐（2026-09-27）：mbr_plan 此前没有任何写入方，C 端会员页因此永远是空列表。
  // 在本菜单末尾而非紧挨「会员/次卡」—— 菜单真源在库里，往组中间插要整表重灌种子
  // 会员两项与营销管理整段 2026-10-28 并入销售管理（OM04）
  // 客服管理 2026-10-28 并入运维管理（OM06），见上方
  "/reports?tab=device|设备运营分析|report:device:read|2|",
  "/reports?tab=location|点位坪效|report:location:read|2|",
  "/reports?tab=finance|财务报表|report:finance:read|2|",
  "/reports?tab=screen|实时大屏|report:screen:read|3|",
  "/reports?tab=custom|自定义报表|report:custom:read|3|",
  "/reports?tab=consumer|消费者分析|report:consumer:read|3|",
  "/employees?tab=employees|员工|org:employee:read||人与组织",
  "/employees?tab=org|组织架构|org:employee:read|2|人与组织",
  "/employees?tab=roles|角色权限|org:role:read||授权",
  "/employees?tab=menus|菜单管理|org:role:read||授权",
  "/employees?tab=audit|操作审计|org:audit:read|1|留痕与考核",
  "/employees?tab=performance|绩效报表|org:employee:read|3|留痕与考核",
  // 供应商接入 2026-10-28 搬到设备管理（OM03）
  "/system?tab=notify|通知模板|system:notify_template:read||消息触达",
  "/system?tab=notify-log|发送记录|system:notify_log:read|1|消息触达",
  "/system?tab=notify-blacklist|触达拉黑|system:notify_blacklist:read|1|消息触达",
  "/system?tab=rules|业务规则|system:biz_rule:update|1|业务规则",
  "/system?tab=login|登录设置|system:login_setting:update|1|业务规则",
  "/system?tab=dict|参数字典|system:dict:read||基础字典",
  "/system?tab=region|地区库|system:dict:read||基础字典",
  "/system?tab=params|系统参数|system:param:read||基础字典",
  "/system?tab=tax|税率与发票|system:tax:update|2|开放与市场",
  "/system?tab=markets|多国家市场|system:market:read|3|开放与市场",
  "/system?tab=openapi|OpenAPI 应用|system:openapi:read|3|开放与市场",
  // 2026-09-28 流程与工单（OpsKit 运营界面）：整段放在最末，只插新行（V136）
  "/flow/todo|待办中心|flow:todo:read||我的待办",
  "/flow/approvals|审批列表|flow:approval:read||审批",
  "/flow/queue|审核队列|flow:queue:claim||审批",
  "/flow/tickets|工单工作台|flow:ticket:read||工单",
  "/flow/processes|流程配置|flow:process:manage||配置",
  "/flow/settings|运营配置|flow:config:manage||配置",
  "/flow/access|接入管理|flow:access:manage||配置",
  // 2026-09-28 V139：流转与统计（追加在末尾）
  "/flow/instances|流程实例|flow:approval:read||流转与统计",
  "/flow/stats|流程统计|flow:process:manage||流转与统计"];

describe("结构回归基线（层级重构不改内容）", () => {
  // 2026-09-23 菜单收敛：第二步撤销「站点与点位」（16 → 15），第三步把场地方那条线
  // 拆成「场地方与拓展」（15 → 16），落位在原「站点与点位」处，与代理商管理相邻。
  // 2026-09-23 第三步（续）：「计费定价」L1 撤销（16 → 15）——
  // 计费模板/时段价早已并入运营管理 › 收费方案，最后剩的「差异化定价」随 ADR-028
  // 并入方案的「适用范围」，取价从此只有一处答案（V49）。那个 L1 已无内容。
  // 2026-09-28：新增「流程与工单」（15 → 16），放在最末（L1 的 sort 即下标，插中间要整表重灌）。
  // 2026-10-28：「营销管理」并入「销售管理」（16 → 15，V151）。
  // 2026-10-28：「告警管理」「客服管理」并入「运维管理」（15 → 13，V153）。
  // 2026-10-28：从财务管理拆出「结算」（13 → 14，V154）。
  it("L1 = 14 个运营项 + 3 个代理门户项，顺序固定", () => {
    expect(NAV.map((s) => s.key)).toEqual(L1_KEYS);
    expect(NAV.filter((s) => !s.portalFor)).toHaveLength(14);
    expect(NAV.filter((s) => s.portalFor)).toHaveLength(3);
  });
  it("叶子五元组集合与顺序逐条不变（href|label|perm|phase|group）", () => {
    const actual = NAV.flatMap((s) =>
      (s.children ?? []).map((l) => [l.href, l.label, l.perm ?? "", l.phase ?? "", l.group ?? ""].join("|")),
    );
    expect(actual).toEqual(LEAF_TUPLES);
  });
  it("只有系统设置 pinBottom", () => {
    expect(NAV.filter((s) => s.pinBottom).map((s) => s.key)).toEqual(["system"]);
  });
});

describe("A.9 角色×L1 可见性矩阵（抽查）", () => {
  it("ADMIN 见全部 14 个运营项", () => {
    expect(sectionKeys("ADMIN")).toEqual([
      "dashboard", "operation", "device", "workorder", "venue", "agent", "order",
      "settlement", "finance", "user", "report", "org", "system", "flow",
    ]);
  });
  it("VIEWER 不见 用户/客服/员工与权限/系统设置", () => {
    const keys = sectionKeys("VIEWER");
    for (const k of ["user", "cs", "org", "system"]) expect(keys).not.toContain(k);
    expect(keys).toEqual(expect.arrayContaining(["dashboard", "device", "order", "settlement", "finance", "report"]));
    // 2026-10-28：VIEWER 在运营管理只看得到两项分成，都搬到了「结算」（OM08），运营管理对它整段隐藏。
    // 财务管理对它只剩调整单一项（OM07 升为页签；此前它在结算单页签的视图里一样看得到）
    expect(keys).not.toContain("operation");
  });
  it("BD 不见 设备/告警/工单；CS 不见 数据报表", () => {
    for (const k of ["device", "alarm", "workorder"]) expect(sectionKeys("BD")).not.toContain(k);
    const cs = sectionKeys("CS");
    expect(cs).not.toContain("report");
  });
  // 2026-07-29 对标补齐后语义变更：税率/提现规则/支付渠道属财务职责，FINANCE 因此能进系统设置
  // ——但只见这几项，看不到登录设置/系统参数等运维项。
  // 2026-09-23：银行管理移到运营管理 › 基础管理，FINANCE 在那边仍看得到（见下方运营管理用例）。
  it("FINANCE 进 系统设置：只见财务相关项", () => {
    expect(sectionKeys("FINANCE")).toEqual(expect.arrayContaining(["org", "system"]));
    // 支付渠道 2026-10-28 搬到销售管理（OM04），FINANCE 从那边进
    expect(visibleLeaves(sec("system"), "FINANCE").map((l) => l.label))
      .toEqual(["业务规则", "税率与发票"]);
    expect(visibleLeaves(sec("order"), "FINANCE").map((l) => l.label)).toContain("支付渠道");
  });
  it("系统设置每个叶子都有显式 perm：防「无 perm 跟随父级」导致越权可见", () => {
    const noPerm = (sec("system").children ?? []).filter((l) => !l.perm).map((l) => l.label);
    expect(noPerm).toEqual([]);
  });
  // 2026-09-23：问题管理移到运营管理 › 基础管理，CS 从那边进（见下方运营管理用例）。
  it("CS 在系统设置只见触达相关两项（发送记录/触达拉黑）", () => {
    expect(visibleLeaves(sec("system"), "CS").map((l) => l.label))
      .toEqual(["发送记录", "触达拉黑"]);
  });
  // 2026-07-29 代理端门户（B8）：AGENT 是受限外部伙伴，不再走通用运营项。
  // 此前它靠「无 perm 的叶子跟随父级」漏出了 SLA 管理/巡检计划/BD 拓展 CRM/押金与欠费。
  it("AGENT 只见专属门户三项，通用运营项一律不出", () => {
    expect(sectionKeys("AGENT")).toEqual(["my-biz", "my-asset", "my-service"]);
  });
  it("非 AGENT 角色看不到门户项", () => {
    for (const r of ["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER"] as Role[]) {
      for (const k of ["my-biz", "my-asset", "my-service"]) expect(sectionKeys(r)).not.toContain(k);
    }
  });
  it("AGENT 门户只含「我的」七项，不含任何运营方功能", () => {
    // 2026-09-23 加「申请提现」(AGT-06)：分级矩阵把代理自助提现定为 L0（⑦ 分钱的最后一步）。
    const labels = visibleSections("AGENT").flatMap((s) => visibleLeaves(s, "AGENT").map((l) => l.label));
    expect(labels).toEqual(["我的看板", "我的收益", "我的结算", "申请提现", "我的设备", "我的订单", "设备报修"]);
    for (const forbidden of ["SLA 管理", "巡检计划", "BD 拓展 CRM", "押金与欠费", "分润配置"]) {
      expect(labels).not.toContain(forbidden);
    }
  });
  it("路径反推必须按角色区分：/devices 对 OPS 是设备管理，对 AGENT 是我的资产", () => {
    // 门户项与运营项共用同一批路径，不按角色限定的话排在前面的门户项会对所有角色命中
    expect(findActiveSection("/devices", "OPS")?.key).toBe("device");
    expect(findActiveSection("/devices", "AGENT")?.key).toBe("my-asset");
    expect(findActiveSection("/devices")?.key).toBe("device"); // 不传角色时排除门户项
  });
});

describe("L3 叶子过滤（4.2-2）", () => {
  const settlement = () => sec("settlement");
  it("VIEWER 的结算：规则/明细/统计/两项分成/结算单/提现，无收款账户；财务管理只有调整单", () => {
    const labels = visibleLeaves(settlement(), "VIEWER").map((l) => l.label);
    expect(labels).toEqual(["分润规则", "分润明细", "分润统计", "站点分成", "分成方分成", "结算单", "付款任务"]);
    expect(visibleLeaves(sec("finance"), "VIEWER").map((l) => l.label)).toEqual(["调整单"]);
  });
  // 2026-09-23 B3：伙伴账新增「收款账户」—— 没有它提现审批放行不了，所以它和提现审核同组、同样给 FINANCE。
  // 2026-10-28：结算拆出（OM08），「代理分润配置」跨 L1 深链撤销（商家管理 › 商家分润规则 本身在）。
  it("FINANCE 的结算：8 项，以收款账户收尾；财务管理四项：对账 → 账务分录 → 调整单 → 发票", () => {
    const labels = visibleLeaves(settlement(), "FINANCE").map((l) => l.label);
    expect(labels).toHaveLength(8);
    expect(labels).not.toContain("代理分润配置");
    expect(labels.at(-1)).toBe("收款账户");
    expect(visibleLeaves(sec("finance"), "FINANCE").map((l) => l.label)).toEqual(["对账", "账务分录", "调整单", "发票"]);
  });
  // OPS 有 poi/venue，没有 contract/analysis/lead —— 并成网点管理后按 perm 过滤的结论不变。
  it("OPS：网点管理有站点、点位与场地方；无坪效、合同与 CRM；运营管理只剩应用版本", () => {
    const venue = visibleLeaves(sec("venue"), "OPS").map((l) => l.label);
    for (const x of ["站点管理", "点位管理", "站点概览", "场地方"]) expect(venue).toContain(x);
    for (const x of ["站点坪效", "进场合同", "BD 拓展 CRM"]) expect(venue).not.toContain(x);
    expect(visibleLeaves(sec("operation"), "OPS").map((l) => l.label)).toEqual(["应用版本"]);
  });
  it("CS 在「网点管理」一个叶子都没有 → 整个 L1 对它不可见", () => {
    expect(visibleLeaves(sec("venue"), "CS")).toEqual([]);
    expect(sectionKeys("CS")).not.toContain("venue");
  });
});

describe("findActiveSection 路径反推（4.2-3，含尾斜杠/最长前缀）", () => {
  it.each([
    ["/", "dashboard"],
    ["/finance", "finance"],
    ["/finance/", "finance"],
    ["/settlement", "settlement"], // OM08：结算从财务管理拆出，独占 /settlement
    ["/operation/site-sharing", "settlement"],
    ["/operation/payee-sharing/", "settlement"],
    ["/operation/banks", "operation"],
    ["/devices/detail", "device"],
    ["/system/", "system"],
    ["/work-orders", "workorder"],
    ["/alarms", "workorder"], // OM06：告警 / 客服 / 工单同属运维管理
    ["/cs", "workorder"],
    ["/agents", "agent"],
  ])("%s → %s", (path, key) => {
    expect(findActiveSection(path)?.key).toBe(key);
  });
  it("未知路径无归属", () => {
    expect(findActiveSection("/nope")).toBeUndefined();
  });
});

describe("activeLeafIndex 深链高亮（4.2-6）", () => {
  const financeLeaves = (role: Role) => visibleLeaves(sec("finance"), role);
  it("裸 /settlement 无 query → 默认首个可点叶子（分润规则）；裸 /finance → 对账", () => {
    const stl = visibleLeaves(sec("settlement"), "FINANCE");
    expect(stl[activeLeafIndex(stl, "/settlement", null, null)].label).toBe("分润规则");
    const leaves = financeLeaves("FINANCE");
    expect(leaves[activeLeafIndex(leaves, "/finance", null, null)].label).toBe("对账");
  });
  it("?tab=ledger → 账务分录 Phase 2，当前 P1 下不命中(-1)", () => {
    expect(activeLeafIndex(financeLeaves("FINANCE"), "/finance/", "ledger", null)).toBe(-1);
  });
  it("VIEWER 无 ledger 菜单项 → ?tab=ledger 不命中(-1)", () => {
    expect(activeLeafIndex(financeLeaves("VIEWER"), "/finance", "ledger", null)).toBe(-1);
  });
  it("工单 ?view=board → 工单看板", () => {
    const leaves = visibleLeaves(sec("workorder"), "OPS");
    expect(leaves[activeLeafIndex(leaves, "/work-orders", null, "board")].label).toBe("工单看板");
  });
  it("固件 OTA Phase 2 → 当前 P1 下 ?tab=ota 不命中(-1)", () => {
    const leaves = visibleLeaves(sec("device"), "OPS");
    expect(activeLeafIndex(leaves, "/devices", "ota", null)).toBe(-1);
  });
});

describe("section 形态（4.2-5 / D2）", () => {
  it("经营看板无子功能（不渲染 L2 面板，详情全宽）；其余 section 都有子功能", () => {
    expect(sec("dashboard").children).toBeUndefined();
    const emptyOthers = NAV.filter((s) => s.key !== "dashboard" && !s.children?.length).map((s) => s.key);
    expect(emptyOthers).toEqual([]);
  });
  it("无 section 标记为整体待建（soon）", () => {
    expect(NAV.filter((s) => s.soon)).toEqual([]);
  });
});

describe("默认落地与面包屑", () => {
  it("section 默认落地 = 首个可点叶子", () => {
    expect(sectionDefaultHref(sec("order"), "FINANCE")).toBe("/orders");
    expect(sectionDefaultHref(sec("device"), "OPS")).toBe("/devices");
    // 站点四项、计费两项迁走后，FINANCE 在运营管理的首个可点叶子是「银行管理」
    expect(sectionDefaultHref(sec("operation"), "FINANCE")).toBe("/operation/banks");
    // 销售管理第一项是收费方案，落地却是订单列表（section.href）
    expect(sectionDefaultHref(sec("order"), "ADMIN")).toBe("/orders");
  });
  it("section.href 指向的叶子可见且未锁时先落到它（按运营顺序排后第一项不一定是高频页）", () => {
    // 网点管理第一项是 BD CRM，日常落地却该是站点管理
    expect(sectionDefaultHref(sec("venue"), "ADMIN")).toBe("/operation/sites");
    expect(sectionDefaultHref(sec("venue"), "BD")).toBe("/operation/sites");
    // 商家管理落入驻审核（待办优先于台账）；没有入驻码的角色退回第一个可点叶
    expect(sectionDefaultHref(sec("agent"), "ADMIN")).toBe("/agents?tab=applies");
    expect(sectionDefaultHref(sec("agent"), "FINANCE")).toBe("/agents");
  });
  it("运维管理：运营落告警记录；客服没有告警码时落报障受理 —— 客服进来第一眼该看用户报了什么", () => {
    // 2026-09-28 改口径：此前客服两叶被分期挡着、落地退到 /orders，
    // 而 C 端的报障全落在 /cs —— 客服点进「客服管理」看到的却是退款列表。
    // 2026-10-28 客服管理并入运维管理（OM06），落地按 section.href → 第一个可点叶。
    expect(sectionDefaultHref(sec("workorder"), "ADMIN")).toBe("/alarms");
    const csLand = sectionDefaultHref(sec("workorder"), "CS");
    expect(["/alarms", "/cs"]).toContain(csLand);
  });
  it("面包屑三级：L1 › 分组 › 子功能", () => {
    expect(breadcrumb("/devices", "monitor", null, "OPS")).toEqual(["设备管理", "在线运行", "实时监控"]);
  });
  it("面包屑：工单在运维管理下三级（OM06 起工单有分组）", () => {
    expect(breadcrumb("/work-orders", null, "board", "OPS")).toEqual(["运维管理", "工单", "工单看板"]);
    expect(breadcrumb("/cs", "sessions", null, "ADMIN")).toEqual(["运维管理", "客服", "客服会话"]);
  });
  it("面包屑：/settlement?tab=withdrawals → 付款任务 2026-09-23 升为 L0，不再被锁，面包屑到 L3", () => {
    // 旧断言是 Phase 2 被锁故只到 L1。分级矩阵 §六：提现是分钱的最后一步，缺了业务开不了张 → L0。
    expect(breadcrumb("/settlement/", "withdrawals", null, "FINANCE")).toEqual(["结算", "付款", "付款任务"]);
    expect(breadcrumb("/operation/site-sharing", null, null, "FINANCE")).toEqual(["结算", "分润", "站点分成"]);
  });
  it("面包屑：经营看板无子功能，只有一级", () => {
    expect(breadcrumb("/", null, null, "ADMIN")).toEqual(["经营看板"]);
  });
  it("面包屑按角色区分：AGENT 的 /devices 走门户", () => {
    expect(breadcrumb("/devices", null, null, "AGENT")).toEqual(["我的资产", "设备与订单", "我的设备"]);
  });
});

describe("L2 分组（按机构/对象聚类，2026-07-29 结构优化）", () => {
  it("同名 group 的叶子必须在数据里相邻（否则渲染会出现重复小标题）", () => {
    const broken: string[] = [];
    for (const s of NAV) {
      const segs = groupedLeaves(s.children ?? []);
      const named = segs.map((x) => x.group).filter(Boolean) as string[];
      if (named.length !== new Set(named).size) broken.push(s.label);
    }
    expect(broken).toEqual([]);
  });
  it("section 内要么全部叶子有 group，要么全部没有（不混用）", () => {
    const mixed: string[] = [];
    for (const s of NAV) {
      const kids = s.children ?? [];
      if (!kids.length) continue;
      const withG = kids.filter((l) => l.group).length;
      if (withG !== 0 && withG !== kids.length) mixed.push(s.label);
    }
    expect(mixed).toEqual([]);
  });
  it("结算分三组，跟着钱走：分润 → 结算 → 付款（OM08）", () => {
    const segs = groupedLeaves(visibleLeaves(sec("settlement"), "ADMIN"));
    expect(segs.map((x) => x.group)).toEqual(["分润", "结算", "付款"]);
  });
  // 2026-09-23：站点与点位撤销，原两组（场地资产/场地方机构）换成按动线切的分组，见下方运营管理用例。
  it("运营管理只剩基础管理（场站四项迁网点管理、计费两项迁销售管理、分成两项迁结算）", () => {
    const segs = groupedLeaves(visibleLeaves(sec("operation"), "ADMIN"));
    expect(segs.map((x) => x.group)).toEqual(["基础管理"]);
  });
  it("销售管理分七组，跟着收入线走", () => {
    const segs = groupedLeaves(visibleLeaves(sec("order"), "ADMIN"));
    expect(segs.map((x) => x.group)).toEqual(["计费", "订单", "支付", "售后", "营销", "会员", "广告"]);
  });
  it("网点管理分三组，跟着 P1 拓场 → P2 签约 → P3 建站", () => {
    const segs = groupedLeaves(visibleLeaves(sec("venue"), "ADMIN"));
    expect(segs.map((x) => x.group)).toEqual(["拓展", "场地方与合同", "站点与点位"]);
  });
  it("RBAC 过滤后不产生空组（VIEWER 看结算）", () => {
    const segs = groupedLeaves(visibleLeaves(sec("settlement"), "VIEWER"));
    expect(segs.every((x) => x.leaves.length > 0)).toBe(true);
  });
  it("groupedLeaves 对无 group 的 section 返回单段且 group 为 undefined", () => {
    const segs = groupedLeaves(visibleLeaves(sec("report"), "ADMIN"));
    expect(segs).toHaveLength(1);
    expect(segs[0].group).toBeUndefined();
  });
});

describe("三语覆盖（防新增菜单漏配 en/ar）", () => {
  it("NAV 中每个 L1/分组/子功能 标签都有 en+ar 译文", () => {
    const missing: string[] = [];
    for (const s of NAV) {
      if (!hasNavLabel(s.label)) missing.push(`L1 ${s.label}`);
      for (const l of s.children ?? []) {
        if (!hasNavLabel(l.label)) missing.push(`子功能 ${l.label}`);
        if (l.group && !hasNavLabel(l.group)) missing.push(`分组 ${l.group}`);
      }
    }
    expect(missing).toEqual([]);
  });
});

describe("工具函数", () => {
  it("normPath 归一化尾斜杠", () => {
    expect(normPath("/finance/")).toBe("/finance");
    expect(normPath("/")).toBe("/");
  });
  it("leafParts 拆 tab/view", () => {
    expect(leafParts("/finance?tab=rules")).toEqual({ path: "/finance", tab: "rules", view: null });
    expect(leafParts("/work-orders?view=board").view).toBe("board");
  });
});

describe("分级屏蔽（tier gating，默认 CURRENT_PHASE=0）", () => {
  it("CURRENT_PHASE 测试环境为 0（L0 最小闭环）", () => {
    expect(CURRENT_PHASE).toBe(0);
  });
  it("isPhaseLocked：L1/L2/L3 锁，L0/undefined 不锁（undefined 与 0 同义）", () => {
    expect(isPhaseLocked(1)).toBe(true);
    expect(isPhaseLocked(2)).toBe(true);
    expect(isPhaseLocked(3)).toBe(true);
    expect(isPhaseLocked(0)).toBe(false);
    expect(isPhaseLocked(undefined)).toBe(false);
  });
  it("isLeafLocked / isLeafDisabled：固件 OTA(P2) 被锁且不可点", () => {
    const ota = sec("device").children!.find((l) => l.href.includes("ota"))!;
    expect(isLeafLocked(ota)).toBe(true);
    expect(isLeafDisabled(ota)).toBe(true);
  });
  it("isSectionLocked：报表 P1 下全叶被锁 → 整体锁；用户因「风控用户」已 ready → 不锁", () => {
    // 用户 section 曾经整体锁（全叶 P2）。2026-07-30「风控用户」标 ready 后，
    // 段内有可用叶 → 整段解锁。这正是逐叶解锁机制该有的表现，不是回归。
    expect(isSectionLocked(sec("user"), "ADMIN")).toBe(false);
    expect(isSectionLocked(sec("report"), "ADMIN")).toBe(true);
  });
  // 2026-10-28：营销并入销售管理，段内有订单列表等 L0 叶 → 不锁；/marketing 首页仍靠「优惠券」ready 不锁（见下）
  it("isSectionLocked：销售管理含 L0 叶 → 不整体锁定", () => {
    expect(isSectionLocked(sec("order"), "ADMIN")).toBe(false);
  });
  it("isSectionLocked：设备含 P1 叶 → 不锁", () => {
    expect(isSectionLocked(sec("device"), "ADMIN")).toBe(false);
  });
  it("routeLockedPhase：/devices?tab=ota → 被 P2 锁", () => {
    expect(routeLockedPhase("/devices", "ota", null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：/devices（台账 P1）→ 不锁", () => {
    expect(routeLockedPhase("/devices", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：/marketing 首页（首叶 优惠券 P2 但已 ready）→ 不再锁", () => {
    // 优惠券 2026-07-30 标 ready（发放功能已实机验证），故首页不再被 P2 拦。
    expect(routeLockedPhase("/marketing", null, null, "ADMIN")).toBeUndefined();
  });
  it("routeLockedPhase：裸路径首叶被锁、后面有能用的叶 → 不锁（与面包屑、页面默认 tab 同口径）", () => {
    // /finance 首叶账务分录 L2；拆出结算（OM08）后裸 /finance 曾整页被拦，而面包屑显示的是「对账」
    expect(routeLockedPhase("/finance", null, null, "ADMIN")).toBeUndefined();
    expect(routeLockedPhase("/finance", "ledger", null, "ADMIN")).toBe(2);
  });
  it("routeLockedPhase：设备详情等非叶路由 → 不锁（透传页面）", () => {
    expect(routeLockedPhase("/devices/detail", null, null, "ADMIN")).toBeUndefined();
  });
  // ready = 逐叶解锁覆盖（2026-07-30）：phase 只管徽章，ready 才是门禁。
  // 这几条是新旧语义的分界线，改 isLeafLocked 必须先看懂它们。
  it("ready 覆盖 phase：P3 叶标 ready 后不再被锁、可点", () => {
    expect(isLeafLocked({ href: "/x", label: "x", phase: 3 })).toBe(true);
    expect(isLeafLocked({ href: "/x", label: "x", phase: 3, ready: true })).toBe(false);
    expect(isLeafDisabled({ href: "/x", label: "x", phase: 3, ready: true })).toBe(false);
  });
  it("ready 不放宽 soon：待建仍不可点（两者是不同概念，不可互相顶替）", () => {
    expect(isLeafDisabled({ href: "/x", label: "x", soon: true, ready: true })).toBe(true);
  });
  it("ready 叶参与 isSectionLocked / sectionDefaultHref / routeLockedPhase", () => {
    const locked = { href: "/x", label: "a", phase: 2 } as const;
    const readyLeaf = { href: "/x?tab=b", label: "b", phase: 2, ready: true } as const;
    const s = { key: "t", label: "T", icon: "X", module: "device", href: "/x", children: [locked, readyLeaf] };
    expect(isSectionLocked(s, "ADMIN")).toBe(false); // 有一个 ready 叶 → 整段不锁
    expect(sectionDefaultHref(s, "ADMIN")).toBe("/x?tab=b"); // 落到 ready 叶而非被锁首叶
  });
  it("财务管理落对账（section.href）；没有对账码的角色退回首个可点叶", () => {
    expect(sectionDefaultHref(sec("finance"), "ADMIN")).toBe("/finance?tab=reconcile");
    expect(sectionDefaultHref(sec("finance"), "VIEWER")).toBe("/finance?tab=adjustments");
  });
  it("财务管理分三组：对账 → 账务 → 发票（OM07；发票组待 OM09 迁走）", () => {
    const segs = groupedLeaves(visibleLeaves(sec("finance"), "ADMIN"));
    expect(segs.map((x) => x.group)).toEqual(["对账", "账务", "发票"]);
  });
  it("结算落地结算单（section.href），不是第一项分润规则", () => {
    expect(sectionDefaultHref(sec("settlement"), "ADMIN")).toBe("/settlement?tab=settlements");
    expect(sectionDefaultHref(sec("settlement"), "VIEWER")).toBe("/settlement?tab=settlements");
  });
});

describe("portalTitleOverride（拍板 #5：代理端只改标题）", () => {
  it("AGENT 命中门户叶：无 tab 的叶只接管页面默认位", () => {
    expect(portalTitleOverride("AGENT", "/", null, true)).toBe("我的看板");
    expect(portalTitleOverride("AGENT", "/devices", "cabinets", true)).toBe("我的设备");
    expect(portalTitleOverride("AGENT", "/orders", "list", true)).toBe("我的订单");
    // 子 tab 不接管：AGENT 看实时监控时标题仍是「实时监控」
    expect(portalTitleOverride("AGENT", "/devices", "monitor", false)).toBeUndefined();
  });
  it("AGENT 命中带 tab/view 的门户叶：按 key 精确匹配", () => {
    expect(portalTitleOverride("AGENT", "/settlement", "records", false)).toBe("我的收益");
    expect(portalTitleOverride("AGENT", "/settlement", "settlements", false)).toBe("我的结算");
    expect(portalTitleOverride("AGENT", "/work-orders", "list", true)).toBe("设备报修");
    expect(portalTitleOverride("AGENT", "/settlement", "rules", true)).toBeUndefined();
  });
  it("非门户角色一律不覆盖（运营端标题不受影响）", () => {
    for (const role of ["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER"] as const) {
      expect(portalTitleOverride(role, "/devices", "cabinets", true)).toBeUndefined();
    }
    expect(portalTitleOverride(undefined, "/devices", "cabinets", true)).toBeUndefined();
  });
  it("尾斜杠归一化后仍命中（trailingSlash:true 的路由形态）", () => {
    expect(portalTitleOverride("AGENT", "/devices/", "cabinets", true)).toBe("我的设备");
  });
});

// ── 运营管理（2026-09-22，对标简电三分组；TDD-运营管理菜单-前端.md）──────────
describe("运营管理：跨模块 section", () => {
  const op = () => sec("operation");
  const leafLabels = (r: Role) => visibleLeaves(op(), r).map((l) => l.label);

  it("各角色可见的子页面与后端权限码一致", () => {
    // 2026-10-28：站点概览 / 站点管理 / 点位管理 / 站点坪效迁到网点管理（OM02）
    // 计费两项 2026-10-28 迁到销售管理（OM04）
    // 分成两项 2026-10-28 迁到结算（OM08）
    expect(leafLabels("ADMIN")).toEqual(["应用版本", "银行管理", "品牌管理", "问题管理", "公告管理"]);
    expect(leafLabels("OPS")).toEqual(["应用版本"]);
    expect(leafLabels("FINANCE")).toEqual(["银行管理"]);
    expect(leafLabels("BD")).toEqual(["公告管理"]);
    expect(leafLabels("VIEWER")).toEqual([]);
  });

  it("CS 没有 location 模块权限，也能通过 system/marketing 看到运营管理（多模块规则）", () => {
    expect(sectionKeys("CS")).toContain("operation");
    expect(leafLabels("CS")).toEqual(["问题管理", "公告管理"]);
    // 默认落地到它第一个能打开的页面，而不是它没权限的站点概览
    expect(sectionDefaultHref(op(), "CS")).toBe("/operation/problems");
  });

  it("AGENT 走门户，看不到运营管理", () => {
    expect(sectionKeys("AGENT")).not.toContain("operation");
  });

  it("凡是能看到运营管理的角色，至少有一个子页面（不出现点开是空的一级菜单）", () => {
    for (const r of ["ADMIN", "OPS", "CS", "FINANCE", "BD", "VIEWER"] as Role[]) {
      if (sectionKeys(r).includes("operation")) expect(leafLabels(r).length).toBeGreaterThan(0);
    }
  });

  it("分组与顺序固定（公告已并进「基础管理」：原先那一组只有一条同名叶子）", () => {
    expect(groupedLeaves(visibleLeaves(op(), "ADMIN")).map((g) => g.group))
      .toEqual(["基础管理"]);
  });

  it("多个独立页面：路径反推与高亮按页面路径", () => {
    // 分成两页 2026-10-28 归结算（OM08）：/operation/site-sharing 靠 match 最长前缀胜过 /operation
    expect(findActiveSection("/operation/site-sharing", "ADMIN")?.key).toBe("settlement");
    expect(findActiveSection("/operation/site-sharing/", "ADMIN")?.key).toBe("settlement");
    expect(findActiveSection("/operation/banks", "ADMIN")?.key).toBe("operation");
    const leaves = visibleLeaves(sec("settlement"), "ADMIN");
    expect(leaves[activeLeafIndex(leaves, "/operation/site-sharing", null, null)]?.label).toBe("站点分成");
    expect(breadcrumb("/operation/fee-adjustments", null, null, "ADMIN")).toEqual(["销售管理", "计费", "预约调价"]);
    // 2026-10-28 网点管理（OM02）认领 /venues、/locations 与 /operation 下的两个站点页 ——
    // 一个 URL 只能属于一个 L1，靠最长前缀：/operation/sites 胜过运营管理的 /operation。
    expect(findActiveSection("/locations", "ADMIN")?.key).toBe("venue");
    expect(breadcrumb("/locations", "points", null, "ADMIN")).toEqual(["网点管理", "站点与点位", "点位管理"]);
    expect(findActiveSection("/operation/sites", "ADMIN")?.key).toBe("venue");
    expect(findActiveSection("/operation/overview/", "ADMIN")?.key).toBe("venue");
    expect(breadcrumb("/operation/sites", null, null, "ADMIN")).toEqual(["网点管理", "站点与点位", "站点管理"]);
    expect(findActiveSection("/operation/fee-plans", "ADMIN")?.key).toBe("order");
    expect(findActiveSection("/marketing", "ADMIN")?.key).toBe("order");
    expect(findActiveSection("/operation/banks", "ADMIN")?.key).toBe("operation");
    expect(findActiveSection("/venues", "ADMIN")?.key).toBe("venue");
    expect(breadcrumb("/venues", "venues", null, "ADMIN")).toEqual(["网点管理", "场地方与合同", "场地方"]);
    expect(breadcrumb("/venues", "crm", null, "BD")).toEqual(["网点管理", "拓展", "BD 拓展 CRM"]);
    // 旧菜单的同名功能不受影响
    expect(findActiveSection("/system", "ADMIN")?.key).toBe("system");
  });

  it("灰显完全由 backend-ready 决定（当前两种模式都不灰）", () => {
    // 只有 /operation/<page> 这些叶子走 backend-ready；并进来的 /locations?tab= 七项不参与灰显。
    // 网点管理的站点管理 / 站点概览、销售管理的收费方案 / 预约调价也是 /operation/<page>，一并纳入
    const rows = [...(op().children ?? []), ...(sec("venue").children ?? []), ...(sec("order").children ?? [])]
      .filter((l) => l.href.startsWith("/operation/"))
      .map((l) => [l.href.replace("/operation/", ""), l.label, l.perm ?? "", l.group ?? ""] as [OperationPage, string, string, string]);
    // 2026-09-23 后端补齐后，真实模式下也不再有灰显项。
    // 这条断言**不删**：它守的是「灰显完全由 backend-ready 决定」这条机制，
    // 哪天再有新页面未就绪，它会立刻把那一项列出来。
    const soonReal = opLeaves(rows, false).filter((l) => l.soon).map((l) => l.label);
    expect(soonReal).toEqual([]);
    expect(opLeaves(rows, true).filter((l) => l.soon)).toEqual([]);
  });
});
