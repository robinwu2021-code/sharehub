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
    instances: "流程实例", instanceDetail: "实例详情", subject: "对账", stats: "流程统计",
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
  category: { NEW: "新建", IN_PROGRESS: "处理中", WAITING: "等待中", DONE: "已完成", CANCELLED: "已取消" },
  membersTruncated: "只列出了前 {n} 人（共 {total} 人）",
  /** OpsKit 错误键（whyNot 与 403 同键、服务不给文案）→ 三语。键去掉 opskit. 前缀、按点分组 */
  err: {
    approval: {
      cross_step: "跨步互斥：你已在本轮前一步做过决定，不能再办这一步", self_review: "提交人不能审批自己提交的单",
      reason_required: "驳回必须填写原因",
    },
    task: { not_candidate: "你不是这一步的审批人" },
    process: { not_allowed: "你不在这个队列里，不能领取" },
    template: { countersign_not_enumerable: "会签只能用于成员 / 组 / 字段指定（角色、权限码的人选不可枚举）", code_taken: "这个代号已被一条流程占用" },
    state_changed: "这张单已被他人处理，请刷新后再看",
    permission: { denied: "服务端拒绝：当前身份没有这项权限，请联系管理员在流程平台里开通" },
    ticket: {
      field_invalid: "工单内容有误", unknown_option: "不是可选的选项", option_disabled: "这个选项已停用", unknown_field: "本类型没有这个字段",
      not_on_create: "建单时不能填", expect_text: "应为文字", expect_number: "应为数字", expect_option: "应为一个选项", expect_option_list: "应为一组选项",
      type_disabled: "这个工单类型已停用", unknown_type: "工单类型不存在", unknown_transition: "当前状态不能做这个操作", closed: "工单已结",
    },
    validation: { required: "必填", invalid: "格式不对", too_long: "太长了", too_many: "太多了", one_of: "取值不在允许范围内", duplicate: "重复了" },
    sla: {
      unreachable_target: "这一行永远匹配不到：前面有兜底行（不带条件），兜底行只能放最后", unknown_metric: "本策略没有这个指标",
      never_starts: "这个指标永远不会开始计时", never_stops: "这个指标永远不会停止计时", stop_on_create: "不能在建单时就停止计时",
      metric_code_format: "指标代号格式不对", unknown_policy: "SLA 策略不存在",
    },
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
    instances: "Process Instances", instanceDetail: "Instance details", subject: "Reconcile", stats: "Process Stats",
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
  category: { NEW: "New", IN_PROGRESS: "In progress", WAITING: "Waiting", DONE: "Done", CANCELLED: "Cancelled" },
  membersTruncated: "Showing the first {n} of {total} people",
  err: {
    approval: {
      cross_step: "Cross-step rule: you already decided an earlier step in this round", self_review: "Requesters cannot approve their own requests",
      reason_required: "A reason is required to reject",
    },
    task: { not_candidate: "You are not an approver for this step" },
    process: { not_allowed: "You are not in this queue and cannot claim" },
    template: { countersign_not_enumerable: "\"All must approve\" only works with members, groups or a submitted field (roles and permissions cannot be enumerated)", code_taken: "This code is already used by a process" },
    state_changed: "Someone else has already handled this — refresh and try again",
    permission: { denied: "Refused by the service: your identity lacks this permission — ask an administrator to grant it in the workflow platform" },
    ticket: {
      field_invalid: "The ticket has invalid fields", unknown_option: "Not one of the options", option_disabled: "This option is disabled", unknown_field: "This type has no such field",
      not_on_create: "Cannot be set when creating", expect_text: "Should be text", expect_number: "Should be a number", expect_option: "Should be one option", expect_option_list: "Should be a list of options",
      type_disabled: "This ticket type is disabled", unknown_type: "Ticket type not found", unknown_transition: "This action is not available in the current status", closed: "The ticket is closed",
    },
    validation: { required: "Required", invalid: "Invalid format", too_long: "Too long", too_many: "Too many", one_of: "Not an allowed value", duplicate: "Duplicate" },
    sla: {
      unreachable_target: "This row can never match: a catch-all row (no condition) comes before it — the catch-all must be last", unknown_metric: "This policy has no such metric",
      never_starts: "This metric would never start", never_stops: "This metric would never stop", stop_on_create: "Cannot stop timing on creation",
      metric_code_format: "Invalid metric code", unknown_policy: "SLA policy not found",
    },
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
    instances: "مثيلات المسارات", instanceDetail: "تفاصيل المثيل", subject: "مطابقة", stats: "إحصاءات المسارات",
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
  category: { NEW: "جديد", IN_PROGRESS: "قيد المعالجة", WAITING: "بالانتظار", DONE: "مكتمل", CANCELLED: "ملغى" },
  membersTruncated: "يُعرض أول {n} من {total} شخصًا",
  err: {
    approval: {
      cross_step: "قاعدة الخطوات: اتخذت قرارًا في خطوة سابقة من هذه الجولة", self_review: "لا يمكن لمقدّم الطلب الموافقة على طلبه",
      reason_required: "يجب ذكر سبب الرفض",
    },
    task: { not_candidate: "لست من معتمدي هذه الخطوة" },
    process: { not_allowed: "لست ضمن هذه القائمة ولا يمكنك الاستلام" },
    template: { countersign_not_enumerable: "الموافقة الجماعية تصلح فقط للأعضاء أو المجموعات أو حقل مُرسَل (لا يمكن حصر الأدوار والصلاحيات)", code_taken: "هذا الرمز مستخدم في مسار آخر" },
    state_changed: "عالج شخص آخر هذا الطلب — حدّث الصفحة وأعد المحاولة",
    permission: { denied: "رفضت الخدمة: هويتك لا تملك هذه الصلاحية — اطلب من المسؤول منحها في منصة سير العمل" },
    ticket: {
      field_invalid: "في التذكرة حقول غير صالحة", unknown_option: "ليس من الخيارات المتاحة", option_disabled: "هذا الخيار معطّل", unknown_field: "لا يوجد هذا الحقل في هذا النوع",
      not_on_create: "لا يمكن تعيينه عند الإنشاء", expect_text: "يجب أن يكون نصًا", expect_number: "يجب أن يكون رقمًا", expect_option: "يجب أن يكون خيارًا واحدًا", expect_option_list: "يجب أن يكون قائمة خيارات",
      type_disabled: "نوع التذكرة هذا معطّل", unknown_type: "نوع التذكرة غير موجود", unknown_transition: "هذا الإجراء غير متاح في الحالة الحالية", closed: "التذكرة مغلقة",
    },
    validation: { required: "إلزامي", invalid: "صيغة غير صالحة", too_long: "طويل جدًا", too_many: "كثير جدًا", one_of: "قيمة غير مسموح بها", duplicate: "مكرر" },
    sla: {
      unreachable_target: "لن يطابق هذا الصف أبدًا: يسبقه صف شامل (بلا شرط) — يجب أن يكون الصف الشامل الأخير", unknown_metric: "لا يوجد هذا المقياس في هذه السياسة",
      never_starts: "لن يبدأ هذا المقياس أبدًا", never_stops: "لن يتوقف هذا المقياس أبدًا", stop_on_create: "لا يمكن إيقاف التوقيت عند الإنشاء",
      metric_code_format: "رمز المقياس غير صالح", unknown_policy: "سياسة SLA غير موجودة",
    },
  },
} satisfies M;

export const common = { zh, en, ar };
