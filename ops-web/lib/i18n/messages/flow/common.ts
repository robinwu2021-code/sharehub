// 流程与工单：各页共用的文案（时长、SLA、页面名、徽标、守卫提示）。
// 每个命名空间一个文件，三语写在一处；en / ar 用 `satisfies M` 对齐 zh 的形状，
// i18n.test.ts 再校验一次 key 齐平。ar 为机器初译，needs-review。
const zh = {
  dur: { d: "{n} 天", dh: "{d} 天 {h} 小时", h: "{n} 小时", hm: "{h} 小时 {m} 分", m: "{n} 分钟" },
  today: "今天 {t}",
  system: "系统",
  sep: " · ",
  listSep: "；",
  colon: "：",
  none: "—",
  page: {
    todo: "待办中心", approvals: "审批列表", approvalDetail: "审批详情", queue: "审核队列",
    tickets: "工单工作台", ticketDetail: "工单详情", processes: "流程配置", settings: "运营配置", access: "接入管理",
  },
  needPerm: "当前角色没有「{what}」的查看权限（{code}），请联系管理员在角色权限里开通。",
  notReady: "流程与工单尚未接入后端服务（OpsKit），暂不可用。联调完成后开放；在此之前请在原有的工单与审批页面办理。",
  sla: {
    met: "已完成", paused: "已暂停", pausedFor: "已暂停 {d}", breached: "超 {d}", breachedTitle: "已超过时限",
    left: "剩 {d}", warnTitle: "两小时内到期", noLimit: "无时限",
  },
  stat: { aria: "工单统计（点击筛选）", picked: "✓ 已筛选" },
  todoKind: { PROCESS: "审批", TICKET: "工单", POOL: "可抢" },
  approvalStatus: { PENDING: "待审", APPROVED: "已通过", REJECTED: "已驳回", WITHDRAWN: "已撤回", IN_PROGRESS: "审批中" },
  channel: { API: "API 登记", CONSOLE: "控制台" },
  loading: "加载中…",
  membersTruncated: "只列出了前 {n} 人（共 {total} 人）",
  /** OpsKit 错误键（whyNot 与 403 同键、服务不给文案）→ 三语。键去掉 opskit. 前缀、按点分组 */
  err: {
    approval: { cross_step: "跨步互斥：你已在本轮前一步做过决定，不能再办这一步", self_review: "提交人不能审批自己提交的单" },
    task: { not_candidate: "你不是这一步的审批人" },
  },
};
type M = typeof zh;

const en = {
  dur: { d: "{n} d", dh: "{d} d {h} h", h: "{n} h", hm: "{h} h {m} min", m: "{n} min" },
  today: "Today {t}",
  system: "System",
  sep: " · ",
  listSep: "; ",
  colon: ": ",
  none: "—",
  page: {
    todo: "My Tasks", approvals: "Approvals", approvalDetail: "Approval details", queue: "Review Queue",
    tickets: "Ticket Desk", ticketDetail: "Ticket details", processes: "Approval Templates", settings: "SLA & Queues", access: "Integrations",
  },
  needPerm: "Your role cannot view \"{what}\" ({code}). Ask an administrator to grant it in role permissions.",
  notReady: "Workflow & tickets is not connected to its backend service (OpsKit) yet. It opens once integration is done; until then, use the existing work-order and approval pages.",
  sla: {
    met: "Completed", paused: "Paused", pausedFor: "Paused {d}", breached: "{d} over", breachedTitle: "Past the deadline",
    left: "{d} left", warnTitle: "Due within two hours", noLimit: "No deadline",
  },
  stat: { aria: "Ticket stats (click to filter)", picked: "✓ Filtered" },
  todoKind: { PROCESS: "Approval", TICKET: "Ticket", POOL: "Claimable" },
  approvalStatus: { PENDING: "Pending", APPROVED: "Approved", REJECTED: "Rejected", WITHDRAWN: "Withdrawn", IN_PROGRESS: "In review" },
  channel: { API: "Registered via API", CONSOLE: "Console" },
  loading: "Loading…",
  membersTruncated: "Showing the first {n} of {total} people",
  err: {
    approval: { cross_step: "Cross-step rule: you already decided an earlier step in this round", self_review: "Requesters cannot approve their own requests" },
    task: { not_candidate: "You are not an approver for this step" },
  },
} satisfies M;

const ar = {
  dur: { d: "{n} يوم", dh: "{d} يوم {h} ساعة", h: "{n} ساعة", hm: "{h} ساعة {m} دقيقة", m: "{n} دقيقة" },
  today: "اليوم {t}",
  system: "النظام",
  sep: " · ",
  listSep: "؛ ",
  colon: ": ",
  none: "—",
  page: {
    todo: "مهامي", approvals: "الموافقات", approvalDetail: "تفاصيل الموافقة", queue: "قائمة المراجعة",
    tickets: "مكتب التذاكر", ticketDetail: "تفاصيل التذكرة", processes: "قوالب الموافقة", settings: "اتفاقيات الخدمة والقوائم", access: "التكاملات",
  },
  needPerm: "لا يملك دورك صلاحية عرض «{what}» ({code}). اطلب من المسؤول منحها في صلاحيات الدور.",
  notReady: "لم يتم ربط سير العمل والتذاكر بخدمة الخلفية (OpsKit) بعد. ستُتاح بعد اكتمال الربط؛ حتى ذلك الحين استخدم صفحات أوامر العمل والموافقات الحالية.",
  sla: {
    met: "مكتمل", paused: "متوقف مؤقتًا", pausedFor: "متوقف {d}", breached: "متأخر {d}", breachedTitle: "تجاوز المهلة",
    left: "متبقٍ {d}", warnTitle: "يستحق خلال ساعتين", noLimit: "بلا مهلة",
  },
  stat: { aria: "إحصاءات التذاكر (انقر للتصفية)", picked: "✓ مُصفّى" },
  todoKind: { PROCESS: "موافقة", TICKET: "تذكرة", POOL: "قابل للاستلام" },
  approvalStatus: { PENDING: "قيد المراجعة", APPROVED: "مقبول", REJECTED: "مرفوض", WITHDRAWN: "مسحوب", IN_PROGRESS: "قيد الموافقة" },
  channel: { API: "مسجّل عبر API", CONSOLE: "لوحة التحكم" },
  loading: "جارٍ التحميل…",
  membersTruncated: "يُعرض أول {n} من {total} شخصًا",
  err: {
    approval: { cross_step: "قاعدة الخطوات: اتخذت قرارًا في خطوة سابقة من هذه الجولة", self_review: "لا يمكن لمقدّم الطلب الموافقة على طلبه" },
    task: { not_candidate: "لست من معتمدي هذه الخطوة" },
  },
} satisfies M;

export const common = { zh, en, ar };
