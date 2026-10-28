// 导航标签三语 overlay：以 nav.ts 的中文 label 为源 key（nav.ts 保持中文 SSOT 不动），
// 叠加 en/ar。⚠️ ar needs-review（机器生成）。zh 直接用源串。
import type { Locale } from "./index";

type Pair = { en: string; ar: string };

const NAV: Record<string, Pair> = {
  // 域
  "我的": { en: "My Portal", ar: "بوابتي" },
  "我的经营": { en: "My Business", ar: "أعمالي" },
  "我的资产": { en: "My Assets", ar: "أصولي" },
  "我的服务": { en: "My Service", ar: "خدماتي" },
  "我的看板": { en: "My Dashboard", ar: "لوحتي" },
  "我的收益": { en: "My Earnings", ar: "أرباحي" },
  "我的结算": { en: "My Settlements", ar: "تسوياتي" },
  "申请提现": { en: "Request Withdrawal", ar: "طلب سحب" },
  "我的设备": { en: "My Devices", ar: "أجهزتي" },
  "我的订单": { en: "My Orders", ar: "طلباتي" },
  "设备报修": { en: "Report Fault", ar: "الإبلاغ عن عطل" },
  "经营概览": { en: "Business Overview", ar: "نظرة عامة على الأعمال" },
  "设备与订单": { en: "Devices & Orders", ar: "الأجهزة والطلبات" },
  "报修与跟进": { en: "Faults & Follow-up", ar: "الأعطال والمتابعة" },
  "概览": { en: "Overview", ar: "نظرة عامة" },
  "设备运营": { en: "Device Ops", ar: "تشغيل الأجهزة" },
  "渠道与场地": { en: "Channels & Places", ar: "القنوات والمواقع" },
  "交易与资金": { en: "Trade & Finance", ar: "المعاملات والمالية" },
  "用户与服务": { en: "Users & Service", ar: "المستخدمون والخدمة" },
  "数据报表": { en: "Reports", ar: "التقارير" },
  "系统与权限": { en: "System & Access", ar: "النظام والصلاحيات" },
  // 模块
  "经营看板": { en: "Dashboard", ar: "لوحة المعلومات" },
  "设备管理": { en: "Devices", ar: "الأجهزة" },
  // 2026-10-28 告警管理 + 客服管理 + 工单管理合并为「运维管理」（OM06），分三组；
  // 「工单」组名与流程与工单的「工单」组共用一个键（按中文取键），英文沿用 Tickets
  "运维管理": { en: "Operations & Maintenance", ar: "التشغيل والصيانة" },
  "告警": { en: "Alarms", ar: "الإنذارات" },
  "客服": { en: "Customer Service", ar: "خدمة العملاء" },
  "商家管理": { en: "Merchants", ar: "التجار" },
  // 2026-10-28 订单管理 + 营销管理 + 计费 + 会员 + 支付渠道合并为「销售管理」（OM04），分七组
  "销售管理": { en: "Sales", ar: "المبيعات" },
  // 用户管理三组（2026-10-28 按运营顺序，OM05）
  "用户": { en: "Users", ar: "المستخدمون" },
  "风险": { en: "Risk", ar: "المخاطر" },
  "资产": { en: "Assets", ar: "الأصول" },
  "免押白名单": { en: "Deposit-free Whitelist", ar: "القائمة البيضاء المعفاة من التأمين" },
  "充值订单与套餐": { en: "Top-ups & Packages", ar: "الشحن والباقات" },
  "计费": { en: "Pricing", ar: "التسعير" },
  "订单": { en: "Orders", ar: "الطلبات" },
  "支付": { en: "Payments", ar: "المدفوعات" },
  "售后": { en: "After-sales", ar: "خدمة ما بعد البيع" },
  "营销": { en: "Marketing", ar: "التسويق" },
  "会员": { en: "Membership", ar: "العضوية" },
  "广告": { en: "Advertising", ar: "الإعلانات" },
  "财务管理": { en: "Finance", ar: "المالية" },
  "用户管理": { en: "Users", ar: "المستخدمون" },
  "员工与权限": { en: "Staff & Roles", ar: "الموظفون والأدوار" },
  "系统设置": { en: "Settings", ar: "الإعدادات" },
  "流程与工单": { en: "Workflow & Tickets", ar: "سير العمل والتذاكر" },
  "待办中心": { en: "My Tasks", ar: "مهامي" },
  "审批列表": { en: "Approvals", ar: "الموافقات" },
  "审核队列": { en: "Review Queue", ar: "قائمة المراجعة" },
  "工单工作台": { en: "Ticket Desk", ar: "مكتب التذاكر" },
  "流程配置": { en: "Approval Templates", ar: "قوالب الموافقة" },
  "运营配置": { en: "SLA & Queues", ar: "اتفاقيات الخدمة والقوائم" },
  "接入管理": { en: "Integrations", ar: "التكاملات" },
  "流程实例": { en: "Process Instances", ar: "مثيلات المسارات" },
  "流程统计": { en: "Process Stats", ar: "إحصاءات المسارات" },
  "流转与统计": { en: "Tracking & Stats", ar: "التتبع والإحصاءات" },
  "我的待办": { en: "My Work", ar: "عملي" },
  "审批": { en: "Approvals", ar: "الموافقات" },
  "工单": { en: "Tickets", ar: "التذاكر" },
  "配置": { en: "Configuration", ar: "الإعدادات" },
  // L3 分组小标题（按机构/对象聚类）
  "设备档案": { en: "Device Records", ar: "سجلات الأجهزة" },
  "运行监控": { en: "Monitoring", ar: "المراقبة" },
  // 设备管理四组（2026-10-28 按运营顺序，OM03）；原「资产流转」作废
  "仓储与出库": { en: "Stock & Outbound", ar: "المخزون والإخراج" },
  "出库池": { en: "Outbound Pool", ar: "مجمع الإخراج" },
  // 设备生命周期五个视图（V165）
  "部署与安装": { en: "Deploy & Install", ar: "النشر والتركيب" },
  "部署与装机": { en: "Installations", ar: "عمليات التركيب" },
  "待上线": { en: "Pending Go-live", ar: "بانتظار التشغيل" },
  "维护与退出": { en: "Maintenance & Exit", ar: "الصيانة والإخراج" },
  "故障与维修": { en: "Faults & Repairs", ar: "الأعطال والإصلاح" },
  "撤机与回仓": { en: "Removals & Returns", ar: "الإزالة والإرجاع" },
  "报废与遗失": { en: "Scrapped & Lost", ar: "المُتلَف والمفقود" },
  "绑定上线": { en: "Binding & Go-live", ar: "الربط والتشغيل" },
  // 2026-09-23 菜单收敛：「站点与点位」L1 撤销（二步），场地方那条线独立成
  // 「场地方与拓展」L1（三步）。原分组「场地资产 / 场地方机构 / 场站报表」作废。
  // 2026-10-28「场地方与拓展」与运营管理的站点四项合并为「网点管理」（OM02），分三组
  "网点管理": { en: "Network", ar: "إدارة الشبكة" },
  "场地方与合同": { en: "Venues & Contracts", ar: "الأماكن والعقود" },
  "站点与点位": { en: "Sites & Points", ar: "المواقع والنقاط" },
  "拓展": { en: "Business Development", ar: "تطوير الأعمال" },
  "分成": { en: "Revenue Share", ar: "تقاسم الإيرادات" },
  // 商家管理三组（2026-10-28 按运营顺序：进来 → 定钱与资产 → 经营与退出）
  "入驻与档案": { en: "Onboarding & Profiles", ar: "الانضمام والملفات" },
  "分润与划拨": { en: "Commission & Allocation", ar: "العمولة والتخصيص" },
  "经营与退出": { en: "Business & Exit", ar: "الأعمال والخروج" },
  // 财务管理（OM07）：对账 → 账务 → 发票（发票组待 OM09 迁走）；「对账」「发票」两个键组名与叶名共用
  "账务": { en: "Accounting", ar: "المحاسبة" },
  "调整单": { en: "Adjustments", ar: "قيود التسوية" },
  // 2026-10-28 从财务管理拆出「结算」（OM08），分三组：分润 → 结算 → 付款
  "结算": { en: "Settlement", ar: "التسوية" },
  "分润": { en: "Revenue Sharing", ar: "تقاسم العائدات" },
  "付款": { en: "Payouts", ar: "المدفوعات للشركاء" },
  "人与组织": { en: "People & Org", ar: "الأفراد والتنظيم" },
  "授权": { en: "Authorization", ar: "التفويض" },
  "留痕与考核": { en: "Audit & Review", ar: "التدقيق والتقييم" },
  "运营内容": { en: "Content", ar: "المحتوى التشغيلي" },
  "消息触达": { en: "Messaging", ar: "المراسلة" },
  "业务规则": { en: "Business Rules", ar: "قواعد العمل" },
  "基础字典": { en: "Dictionaries", ar: "القواميس الأساسية" },
  "开放与市场": { en: "Open & Markets", ar: "الانفتاح والأسواق" },
  // 子功能
  "设备台账": { en: "Cabinets", ar: "الخزائن" },
  "充电宝管理": { en: "Power Banks", ar: "بنوك الطاقة" },
  "实时监控": { en: "Monitoring", ar: "المراقبة" },
  "远程控制与指令记录": { en: "Remote & Commands", ar: "التحكم والأوامر" },
  "库存余额": { en: "Stock Balance", ar: "رصيد المخزون" },
  "资产差异": { en: "Asset Discrepancies", ar: "فروقات الأصول" },
  "库存调拨": { en: "Inventory", ar: "المخزون" },
  "固件 OTA": { en: "Firmware OTA", ar: "تحديث البرمجيات" },
  "设备日志": { en: "Device Logs", ar: "سجلات الجهاز" },
  "设备编码": { en: "Device Codes", ar: "أكواد الأجهزة" },
  "告警记录": { en: "Alarm Records", ar: "سجلات الإنذار" },
  "告警通知": { en: "Alarm Notices", ar: "إشعارات الإنذار" },
  "告警代码": { en: "Alarm Codes", ar: "أكواد الإنذار" },
  "通知规则": { en: "Notify Rules", ar: "قواعد الإشعار" },
  "工单列表": { en: "List", ar: "القائمة" },
  "工单看板": { en: "Board", ar: "اللوحة" },
  "SLA 管理": { en: "SLA", ar: "اتفاقية الخدمة" },
  "巡检计划": { en: "Inspections", ar: "خطط التفتيش" },
  "站点管理": { en: "Sites", ar: "المواقع" },
  "点位管理": { en: "Points", ar: "النقاط" },
  "场地方": { en: "Venues", ar: "الأماكن" },
  "进场合同": { en: "Contracts", ar: "العقود" },
  "门店 Onboarding": { en: "Store Onboarding", ar: "تسجيل المتاجر" },
  "BD 拓展 CRM": { en: "BD CRM", ar: "إدارة العملاء" },
  "站点坪效": { en: "Site Yield", ar: "أداء المواقع" },
  "门店生命周期": { en: "Store Lifecycle", ar: "دورة حياة المتجر" },
  "入驻审核": { en: "Onboarding Review", ar: "مراجعة الانضمام" },
  "商家档案": { en: "Merchant Profiles", ar: "ملفات التجار" },
  "商家分润规则": { en: "Merchant Commission", ar: "عمولة التجار" },
  "设备/点位划拨": { en: "Allocation", ar: "التخصيص" },
  "商家经营视图": { en: "Merchant Business", ar: "أعمال التجار" },
  "商家绩效": { en: "Merchant Performance", ar: "أداء التجار" },
  "退出与清算": { en: "Exit & Liquidation", ar: "الخروج والتصفية" },
  "商家账号": { en: "Merchant Accounts", ar: "حسابات التجار" },
  "订单列表": { en: "Orders", ar: "الطلبات" },
  "异常订单": { en: "Exceptions", ar: "الطلبات الشاذة" },
  "押金与欠费": { en: "Deposits & Arrears", ar: "الودائع والمتأخرات" },
  "预约订单": { en: "Reservations", ar: "الحجوزات" },
  "投诉订单": { en: "Complaints", ar: "الشكاوى" },
  "退款记录": { en: "Refunds", ar: "المبالغ المستردة" },
  "免费订单": { en: "Free Orders", ar: "طلبات مجانية" },
  "分润统计": { en: "Sharing Summary", ar: "ملخص التقسيم" },
  "注销申请": { en: "Account Deletions", ar: "طلبات حذف الحساب" },
  "开票申请": { en: "Invoice Requests", ar: "طلبات الفواتير" },
  "公告管理": { en: "Announcements", ar: "الإعلانات العامة" },
  "支付渠道": { en: "Payment Channels", ar: "قنوات الدفع" },
  "发送记录": { en: "Delivery Log", ar: "سجل الإرسال" },
  "触达拉黑": { en: "Notify Blacklist", ar: "قائمة حظر الإشعارات" },
  "登录设置": { en: "Sign-in Settings", ar: "إعدادات الدخول" },
  "应用版本": { en: "App Versions", ar: "إصدارات التطبيق" },
  "品牌管理": { en: "Brands", ar: "العلامات التجارية" },
  "银行管理": { en: "Banks", ar: "البنوك" },
  "问题管理": { en: "Issue Types", ar: "أنواع المشكلات" },
  "税率与发票": { en: "Tax & Invoice", ar: "الضريبة والفواتير" },
  "计费模板": { en: "Templates", ar: "قوالب التسعير" },
  "活动/时段价": { en: "Time-based", ar: "تسعير زمني" },
  "分润规则": { en: "Share Rules", ar: "قواعد التقسيم" },
  "分润明细": { en: "Share Records", ar: "سجلات التقسيم" },
  "账务分录": { en: "Ledger", ar: "القيود المحاسبية" },
  "结算单": { en: "Settlements", ar: "التسويات" },
  "付款任务": { en: "Payout Tasks", ar: "مهام الدفع" },
  "收款账户": { en: "Payout Accounts", ar: "حسابات التحويل" },
  "对账": { en: "Reconcile", ar: "المطابقة" },
  "发票": { en: "Invoices", ar: "الفواتير" },
  "用户列表": { en: "Users", ar: "المستخدمون" },
  "风控用户": { en: "Risk Users", ar: "المستخدمون المحفوفون بالمخاطر" },
  "黑名单": { en: "Blacklist", ar: "القائمة السوداء" },
  "会员/次卡": { en: "Members", ar: "الأعضاء" },
  "会员套餐": { en: "Membership Plans", ar: "خطط العضوية" },
  "钱包": { en: "Wallets", ar: "المحافظ" },
  "优惠券": { en: "Coupons", ar: "القسائم" },
  "活动": { en: "Campaigns", ar: "الحملات" },
  "推送触达": { en: "Push", ar: "الإشعارات" },
  "邀请裂变": { en: "Referrals", ar: "الإحالات" },
  "广告位管理": { en: "Ad Slots", ar: "مساحات الإعلان" },
  "广告活动": { en: "Ad Campaigns", ar: "حملات الإعلان" },
  "投放与曝光": { en: "Ad Delivery", ar: "عرض الإعلانات" },
  "报障受理": { en: "Tickets", ar: "تذاكر الأعطال" },
  "客服会话": { en: "Sessions", ar: "المحادثات" },
  "设备运营分析": { en: "Device Analytics", ar: "تحليلات الأجهزة" },
  "点位坪效": { en: "Point Yield", ar: "أداء النقاط" },
  "财务报表": { en: "Financials", ar: "التقارير المالية" },
  "实时大屏": { en: "Live Wall", ar: "الشاشة المباشرة" },
  "自定义报表": { en: "Custom", ar: "تقارير مخصصة" },
  "消费者分析": { en: "Consumer Analytics", ar: "تحليلات المستهلك" },
  "员工": { en: "Employees", ar: "الموظفون" },
  "角色权限": { en: "Roles", ar: "الأدوار" },
  "菜单管理": { en: "Menu Settings", ar: "إعدادات القائمة" },
  "操作审计": { en: "Audit Log", ar: "سجل التدقيق" },
  "组织架构": { en: "Org Chart", ar: "الهيكل التنظيمي" },
  "绩效报表": { en: "Performance", ar: "الأداء" },
  "厂商接入": { en: "Vendor Integration", ar: "تكامل المصنّعين" },
  "我的站点": { en: "My Sites", ar: "مواقعي" },
  "通知模板": { en: "Templates", ar: "قوالب الإشعارات" },
  "参数字典": { en: "Dictionaries", ar: "القواميس" },
  "地区库": { en: "Regions", ar: "المناطق" },
  "系统参数": { en: "Parameters", ar: "المعاملات" },
  "多国家市场": { en: "Markets", ar: "الأسواق الدولية" },
  "OpenAPI 应用": { en: "OpenAPI Apps", ar: "تطبيقات OpenAPI" },
  // 运营管理新菜单（2026-09-22）
  "运营管理": { en: "Operations", ar: "إدارة العمليات" },
  "基础管理": { en: "Base Data", ar: "البيانات الأساسية" },
  "站点概览": { en: "Site Overview", ar: "نظرة عامة على المواقع" },
  "收费方案": { en: "Fee Plans", ar: "خطط الرسوم" },
  "预约调价": { en: "Scheduled Price Changes", ar: "تعديلات الأسعار المجدولة" },
  "站点分成": { en: "Site Revenue Share", ar: "حصص إيرادات المواقع" },
  "分成方分成": { en: "Payee Revenue Share", ar: "حصص المستفيدين" },
};

/** 该中文标签是否已有 en/ar 译文（单测用：防新增菜单漏配三语）。 */
export function hasNavLabel(label: string): boolean {
  return label in NAV;
}

/** 翻译一个导航标签（中文源串）；未命中或 zh 时原样返回。 */
export function tNav(label: string, locale: Locale): string {
  if (locale === "zh") return label;
  return NAV[label]?.[locale] ?? label;
}

/**
 * 菜单节点的译名：**节点自带的优先**，没有才回落这份 overlay。
 *
 * <h3>为什么要这个优先级</h3>
 * 这份 overlay 以**中文标签做 key**。菜单真源迁进库（V67）之后，
 * 在菜单管理里改个名字，key 就对不上了 —— en/ar 静默回落成中文，而不报错。
 * 所以 `iam_menu.name_en/name_ar` 定为真源（V81 已回填这 128 行），
 * 翻译跟着菜单走；这份 overlay 退居兜底，供静态菜单（开关关闭）时使用，
 * 到 P5 删掉 nav.ts 的硬编码数据时一并退役。
 */
export function tNavNode(
  node: { label: string; labelEn?: string; labelAr?: string },
  locale: Locale,
): string {
  if (locale === "zh") return node.label;
  const own = locale === "ar" ? node.labelAr : node.labelEn;
  return own || tNav(node.label, locale);
}
