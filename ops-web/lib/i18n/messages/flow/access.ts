// 接入管理（/flow/access/）。ar 为机器初译，needs-review。
const zh = {
  desc: "平台管理员：接入应用 · webhook 订阅与投递 · 定义版本。凭证只在创建时显示一次。",
  appStatus: { on: "启用", off: "已停用" },
  delivery: { DELIVERED: "成功", RETRYING: "重试中", FAILED: "失败" },
  kind: { PROCESS: "流程", APPROVAL_TEMPLATE: "审批模板", TICKET_TYPE: "工单类型", SLA_POLICY: "SLA 策略", OPTION_SET: "选项集" },
  field: {
    code: { label: "代号", help: "大写字母开头，如 SHAREHUB；创建后不能改" },
    name: { label: "名称" },
    callbackUrl: { label: "回调地址", pattern: "必须是 https 地址", help: "平台把事件推到这里（带签名）" },
    timeZone: { label: "时区", help: "IANA 时区，如 Asia/Dubai；影响该应用的营业时间与统计口径" },
  },
  col: {
    code: "代号", name: "名称", callbackUrl: "回调地址", timeZone: "时区", status: "状态", action: "操作",
    event: "事件", app: "应用", idemKey: "幂等键", duration: "耗时", attempts: "次数", time: "时间",
    kind: "类别", version: "当前版本", publishedAt: "发布时间", publishedBy: "发布方",
  },
  rotate: {
    btn: "轮换凭证",
    title: "轮换「{name}」的凭证？",
    desc: "旧凭证立即失效，该应用在换上新凭证前的调用都会被拒。新凭证只显示一次。",
    confirm: "轮换",
  },
  redeliver: { btn: "立即重投", done: "已重投 {event}：对端返回 {status}" },
  publishedByConsole: "{by}（控制台）",
  apps: { heading: "接入应用", add: "新增应用", empty: "还没有接入应用", createTitle: "新增接入应用" },
  deliveries: {
    heading: "Webhook 投递（最近）",
    hint: "重试：1m · 5m · 30m · 2h · 12h · 24h，共 8 次；同一实例 / 工单内按 seq 判序",
    empty: "最近没有投递记录",
  },
  defs: { heading: "定义版本", hint: "内容哈希即版本；进行中的实例按提交时版本走完", empty: "还没有登记任何定义" },
  secret: {
    title: "{app} 的新凭证",
    close: "我已保存，关闭",
    notice: "这是唯一一次显示明文。关掉后平台也读不出来，丢了只能再轮换一次。",
    copyAria: "复制凭证",
    copied: "已复制",
    copyFailed: "复制失败，请手动选中复制",
  },
};
type M = typeof zh;

const en = {
  desc: "Platform admins: connected apps · webhook subscriptions and deliveries · definition versions. Credentials are shown only once, at creation.",
  appStatus: { on: "Enabled", off: "Disabled" },
  delivery: { DELIVERED: "Delivered", RETRYING: "Retrying", FAILED: "Failed" },
  kind: { PROCESS: "Process", APPROVAL_TEMPLATE: "Approval template", TICKET_TYPE: "Ticket type", SLA_POLICY: "SLA policy", OPTION_SET: "Option set" },
  field: {
    code: { label: "Code", help: "Starts with an uppercase letter, e.g. SHAREHUB; cannot be changed after creation" },
    name: { label: "Name" },
    callbackUrl: { label: "Callback URL", pattern: "Must be an https URL", help: "The platform pushes events here (signed)" },
    timeZone: { label: "Time zone", help: "IANA time zone, e.g. Asia/Dubai; affects this app's business hours and reporting periods" },
  },
  col: {
    code: "Code", name: "Name", callbackUrl: "Callback URL", timeZone: "Time zone", status: "Status", action: "Actions",
    event: "Event", app: "App", idemKey: "Idempotency key", duration: "Duration", attempts: "Attempts", time: "Time",
    kind: "Category", version: "Current version", publishedAt: "Published at", publishedBy: "Published by",
  },
  rotate: {
    btn: "Rotate credential",
    title: "Rotate the credential for \"{name}\"?",
    desc: "The old credential stops working immediately; this app's calls will be rejected until it switches to the new one. The new credential is shown only once.",
    confirm: "Rotate",
  },
  redeliver: { btn: "Redeliver now", done: "Redelivered {event}: endpoint returned {status}" },
  publishedByConsole: "{by} (console)",
  apps: { heading: "Connected apps", add: "Add app", empty: "No connected apps yet", createTitle: "Add connected app" },
  deliveries: {
    heading: "Webhook deliveries (recent)",
    hint: "Retries: 1m · 5m · 30m · 2h · 12h · 24h, 8 attempts in total; ordered by seq within the same instance / ticket",
    empty: "No recent deliveries",
  },
  defs: { heading: "Definition versions", hint: "The content hash is the version; running instances finish on the version they started with", empty: "No definitions registered yet" },
  secret: {
    title: "New credential for {app}",
    close: "I've saved it — close",
    notice: "This is the only time the plaintext is shown. Once closed, the platform cannot read it either; if you lose it, you will have to rotate again.",
    copyAria: "Copy credential",
    copied: "Copied",
    copyFailed: "Copy failed — please select and copy it manually",
  },
} satisfies M;

const ar = {
  desc: "لمسؤولي المنصة: التطبيقات المتصلة · اشتراكات webhook وعمليات التسليم · إصدارات التعريفات. تُعرض بيانات الاعتماد مرة واحدة فقط عند الإنشاء.",
  appStatus: { on: "مفعّل", off: "معطّل" },
  delivery: { DELIVERED: "تم التسليم", RETRYING: "إعادة المحاولة", FAILED: "فشل" },
  kind: { PROCESS: "مسار", APPROVAL_TEMPLATE: "قالب موافقة", TICKET_TYPE: "نوع تذكرة", SLA_POLICY: "سياسة SLA", OPTION_SET: "مجموعة خيارات" },
  field: {
    code: { label: "الرمز", help: "يبدأ بحرف كبير، مثل SHAREHUB؛ لا يمكن تغييره بعد الإنشاء" },
    name: { label: "الاسم" },
    callbackUrl: { label: "عنوان رد الاتصال", pattern: "يجب أن يكون عنوان https", help: "ترسل المنصة الأحداث إلى هنا (موقّعة)" },
    timeZone: { label: "المنطقة الزمنية", help: "منطقة زمنية وفق IANA، مثل Asia/Dubai؛ تؤثر في ساعات عمل التطبيق وفترات الإحصاء" },
  },
  col: {
    code: "الرمز", name: "الاسم", callbackUrl: "عنوان رد الاتصال", timeZone: "المنطقة الزمنية", status: "الحالة", action: "الإجراءات",
    event: "الحدث", app: "التطبيق", idemKey: "مفتاح عدم التكرار", duration: "المدة", attempts: "المحاولات", time: "الوقت",
    kind: "الفئة", version: "الإصدار الحالي", publishedAt: "وقت النشر", publishedBy: "الناشر",
  },
  rotate: {
    btn: "تدوير بيانات الاعتماد",
    title: "تدوير بيانات اعتماد «{name}»؟",
    desc: "تتوقف بيانات الاعتماد القديمة فورًا، وستُرفض طلبات هذا التطبيق حتى يستخدم الجديدة. تُعرض بيانات الاعتماد الجديدة مرة واحدة فقط.",
    confirm: "تدوير",
  },
  redeliver: { btn: "إعادة التسليم الآن", done: "أُعيد تسليم {event}: أعادت الجهة المستقبِلة {status}" },
  publishedByConsole: "{by} (لوحة التحكم)",
  apps: { heading: "التطبيقات المتصلة", add: "إضافة تطبيق", empty: "لا توجد تطبيقات متصلة بعد", createTitle: "إضافة تطبيق متصل" },
  deliveries: {
    heading: "عمليات تسليم webhook (الأخيرة)",
    hint: "إعادة المحاولة: 1m · 5m · 30m · 2h · 12h · 24h، بإجمالي 8 محاولات؛ الترتيب حسب seq داخل النسخة / التذكرة نفسها",
    empty: "لا توجد عمليات تسليم حديثة",
  },
  defs: { heading: "إصدارات التعريفات", hint: "بصمة المحتوى هي الإصدار؛ تكمل النسخ الجارية على الإصدار الذي بدأت به", empty: "لم يُسجَّل أي تعريف بعد" },
  secret: {
    title: "بيانات اعتماد جديدة لـ {app}",
    close: "حفظتها، إغلاق",
    notice: "هذه هي المرة الوحيدة التي يظهر فيها النص الصريح. بعد الإغلاق لا تستطيع المنصة قراءته أيضًا؛ وإن فقدته فعليك التدوير مرة أخرى.",
    copyAria: "نسخ بيانات الاعتماد",
    copied: "تم النسخ",
    copyFailed: "تعذّر النسخ، يُرجى تحديده ونسخه يدويًا",
  },
} satisfies M;

export const access = { zh, en, ar };
