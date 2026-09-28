// SLA 策略（运营配置页的 SLA 一节，components/flow/sla-policies.tsx）。ar 为机器初译，needs-review。
const zh = {
  title: "SLA 策略",
  hint: "一条策略 = 若干指标 × 一张目标表：自上而下第一条条件满足的行生效，最后一行是兜底。改策略只影响之后开的单。",
  add: "新建策略",
  col: { name: "策略", code: "代号", metrics: "指标", rows: "目标行", warn: "预警", version: "当前版本", actions: "操作" },
  rows: "{n} 行",
  edit: "编辑",
  empty: "还没有 SLA 策略 —— 没挂策略的工单与流程不计时",
  editor: { new: "新建 SLA 策略", edit: "编辑：{name}" },
  field: {
    code: "代号", codeHelp: "小写字母开头，如 ops；创建后不能改", name: "名称", nameHelp: "按当前界面语言编辑；其他语种保留原值",
    metrics: "计时指标", warn: "预警比例（%）", warnHelp: "到时限的百分之几时预警，如 80", note: "版本说明",
    notePlaceholder: "这次改了什么（进版本历史），如「紧急单解决时限 4h → 3h」",
  },
  metric: { FIRST_RESPONSE: "首次响应", RESOLUTION: "解决", custom: "{name}（API 登记，此处不改）" },
  target: {
    title: "目标表", when: "条件", priority: "优先级 = {p}", fallback: "其余（兜底）", advanced: "高级条件（API 登记，此处不改）",
    calendar: "计时", allDay: "7×24", minutes: "分钟", metricCol: "{name}（分钟）", add: "添加一行", up: "上移", down: "下移", remove: "删除这一行",
    fallbackLast: "兜底行只能是最后一行", noMetric: "先勾选至少一个指标",
  },
  priority: { URGENT: "紧急", HIGH: "高", NORMAL: "普通", LOW: "低" },
  save: "保存并发布", close: "关闭",
  saved: "已发布新版本 {version}：只影响之后开的单",
  unchanged: "内容没有变化，未生成新版本",
  calendarPending: "工作日历接口后端尚未开放，日历选项暂为演示数据",
};
type M = typeof zh;

const en = {
  title: "SLA policies",
  hint: "A policy = metrics × a target table: the first row whose condition matches applies, the last row is the fallback. Changes only affect items opened from now on.",
  add: "New policy",
  col: { name: "Policy", code: "Code", metrics: "Metrics", rows: "Rows", warn: "Warning", version: "Current version", actions: "Actions" },
  rows: "{n} rows",
  edit: "Edit",
  empty: "No SLA policies yet — tickets and processes without one are not timed",
  editor: { new: "New SLA policy", edit: "Edit: {name}" },
  field: {
    code: "Code", codeHelp: "Starts with a lowercase letter, e.g. ops; cannot change after creation", name: "Name", nameHelp: "Edits the current UI language; other languages are kept",
    metrics: "Timed metrics", warn: "Warning at (%)", warnHelp: "Warn when this share of the limit has passed, e.g. 80", note: "Version note",
    notePlaceholder: "What changed (goes into version history), e.g. \"urgent resolution 4h → 3h\"",
  },
  metric: { FIRST_RESPONSE: "First response", RESOLUTION: "Resolution", custom: "{name} (registered via API, not editable here)" },
  target: {
    title: "Target table", when: "Condition", priority: "Priority = {p}", fallback: "Everything else (fallback)", advanced: "Advanced condition (via API, not editable here)",
    calendar: "Clock", allDay: "24×7", minutes: "min", metricCol: "{name} (min)", add: "Add row", up: "Move up", down: "Move down", remove: "Remove row",
    fallbackLast: "The fallback row must be last", noMetric: "Tick at least one metric first",
  },
  priority: { URGENT: "Urgent", HIGH: "High", NORMAL: "Normal", LOW: "Low" },
  save: "Save & publish", close: "Close",
  saved: "Published version {version}: only affects items opened from now on",
  unchanged: "Nothing changed; no new version",
  calendarPending: "The work-calendar API is not open yet; calendar options are demo data",
} satisfies M;

const ar = {
  title: "سياسات SLA",
  hint: "السياسة = مقاييس × جدول أهداف: يسري أول صف يتحقق شرطه، والصف الأخير احتياطي. التغييرات تسري فقط على ما يُفتح من الآن.",
  add: "سياسة جديدة",
  col: { name: "السياسة", code: "الرمز", metrics: "المقاييس", rows: "الصفوف", warn: "التنبيه", version: "الإصدار الحالي", actions: "الإجراءات" },
  rows: "{n} صفوف",
  edit: "تعديل",
  empty: "لا توجد سياسات SLA بعد — التذاكر والمسارات بلا سياسة لا يُحتسب لها وقت",
  editor: { new: "سياسة SLA جديدة", edit: "تعديل: {name}" },
  field: {
    code: "الرمز", codeHelp: "يبدأ بحرف لاتيني صغير مثل ops؛ لا يمكن تغييره بعد الإنشاء", name: "الاسم", nameHelp: "يُعدَّل بلغة الواجهة الحالية؛ وتبقى اللغات الأخرى",
    metrics: "المقاييس المؤقتة", warn: "نسبة التنبيه (%)", warnHelp: "نبّه عند مرور هذه النسبة من المهلة، مثل 80", note: "ملاحظة الإصدار",
    notePlaceholder: "ما الذي تغيّر (يُسجَّل في سجل الإصدارات)",
  },
  metric: { FIRST_RESPONSE: "الاستجابة الأولى", RESOLUTION: "الحل", custom: "{name} (مسجّل عبر API، لا يُعدَّل هنا)" },
  target: {
    title: "جدول الأهداف", when: "الشرط", priority: "الأولوية = {p}", fallback: "كل ما عدا ذلك (احتياطي)", advanced: "شرط متقدم (عبر API، لا يُعدَّل هنا)",
    calendar: "التوقيت", allDay: "24×7", minutes: "دقيقة", metricCol: "{name} (دقيقة)", add: "إضافة صف", up: "لأعلى", down: "لأسفل", remove: "حذف الصف",
    fallbackLast: "يجب أن يكون الصف الاحتياطي الأخير", noMetric: "اختر مقياسًا واحدًا على الأقل أولًا",
  },
  priority: { URGENT: "عاجل", HIGH: "مرتفع", NORMAL: "عادي", LOW: "منخفض" },
  save: "حفظ ونشر", close: "إغلاق",
  saved: "نُشر الإصدار {version}: يسري فقط على ما يُفتح من الآن",
  unchanged: "لم يتغير شيء؛ لا إصدار جديد",
  calendarPending: "واجهة تقويمات العمل غير متاحة بعد؛ خيارات التقويم بيانات تجريبية",
} satisfies M;

export const sla = { zh, en, ar };
