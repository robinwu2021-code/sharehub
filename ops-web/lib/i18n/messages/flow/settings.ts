// 运营配置（/flow/settings/）：工作日历 · 队列（SLA 策略另见 sla.ts）。ar 为机器初译，needs-review。
const zh = {
  desc: "SLA 规则 · 工作日历 · 队列。",
  notice: "改规则只影响新单；进行中的单按开单时的规则走完。",
  section: { cal: "工作日历", queue: "队列" },
  btn: { edit: "编辑", add: "新增" },
  order: { PRIORITY: "优先级 → 等待时长", DUE: "到期时刻", CREATED: "等待时长" },
  week: { d1: "一", d2: "二", d3: "三", d4: "四", d5: "五", d6: "六", d7: "日" },
  weekday: "周{d}",
  weekdays: "周{days}",
  enumSep: "、",
  qCol: { name: "队列", code: "代号", orderBy: "排序", claimTimeout: "领取超时", actions: "操作" },
  cal: {
    summary: "时区 {tz} · {days} {open}–{close}",
    holidays: " · 节假日 {n} 天（{list}{more}）",
    more: "…",
    holidayDefault: "节假日",
  },
  empty: {
    queue: "还没有队列 —— 领取制审核要先建队列",
  },
  drawer: {
    calEdit: "编辑工作日历", qNew: "新增队列", qEdit: "编辑队列",
  },
  saved: {
    cal: "工作日历已保存",
    queue: "队列已保存",
  },
  calField: {
    name: "名称",
    timeZone: "时区",
    timeZoneHelp: "IANA 时区，如 Asia/Dubai",
    weekdays: "工作日",
    open: "上班时间",
    openMsg: "HH:mm，如 10:00",
    close: "下班时间",
    closeMsg: "HH:mm，如 22:00",
    holidays: "节假日",
    holidaysHelp: "一行一个：日期 名称，如「2026-12-02 国庆」",
  },
  qField: {
    code: "代号",
    codeHelp: "小写字母、数字、连字符，创建后不能改",
    name: "名称",
    orderBy: "排序",
    claimTimeoutMin: "领取超时（分钟）",
    claimTimeoutHelp: "领取后超时未决定，自动放回队列并留痕",
  },
};
type M = typeof zh;

const en = {
  desc: "SLA rules · Work calendars · Queues.",
  notice: "Rule changes only apply to new tickets; tickets already in progress finish under the rules they were opened with.",
  section: { cal: "Work calendars", queue: "Queues" },
  btn: { edit: "Edit", add: "Add" },
  order: { PRIORITY: "Priority → time waiting", DUE: "Due time", CREATED: "Time waiting" },
  week: { d1: "Mon", d2: "Tue", d3: "Wed", d4: "Thu", d5: "Fri", d6: "Sat", d7: "Sun" },
  weekday: "{d}",
  weekdays: "{days}",
  enumSep: ", ",
  qCol: { name: "Queue", code: "Code", orderBy: "Sort order", claimTimeout: "Claim timeout", actions: "Actions" },
  cal: {
    summary: "Time zone {tz} · {days} {open}–{close}",
    holidays: " · {n} holiday days ({list}{more})",
    more: "…",
    holidayDefault: "Holiday",
  },
  empty: {
    queue: "No queues yet — claim-based review needs a queue first",
  },
  drawer: {
    calEdit: "Edit work calendar", qNew: "Add queue", qEdit: "Edit queue",
  },
  saved: {
    cal: "Work calendar saved",
    queue: "Queue saved",
  },
  calField: {
    name: "Name",
    timeZone: "Time zone",
    timeZoneHelp: "IANA time zone, e.g. Asia/Dubai",
    weekdays: "Working days",
    open: "Opening time",
    openMsg: "HH:mm, e.g. 10:00",
    close: "Closing time",
    closeMsg: "HH:mm, e.g. 22:00",
    holidays: "Holidays",
    holidaysHelp: "One per line: date name, e.g. \"2026-12-02 National Day\"",
  },
  qField: {
    code: "Code",
    codeHelp: "Lowercase letters, digits and hyphens; cannot be changed after creation",
    name: "Name",
    orderBy: "Sort order",
    claimTimeoutMin: "Claim timeout (minutes)",
    claimTimeoutHelp: "If no decision is made in time after claiming, it goes back to the queue automatically and is logged",
  },
} satisfies M;

const ar = {
  desc: "قواعد SLA · تقويمات العمل · قوائم الانتظار.",
  notice: "تغيير القواعد يسري على التذاكر الجديدة فقط؛ التذاكر الجارية تكتمل وفق القواعد التي فُتحت بها.",
  section: { cal: "تقويمات العمل", queue: "قوائم الانتظار" },
  btn: { edit: "تعديل", add: "إضافة" },
  order: { PRIORITY: "الأولوية ← مدة الانتظار", DUE: "موعد الاستحقاق", CREATED: "مدة الانتظار" },
  week: { d1: "الاثنين", d2: "الثلاثاء", d3: "الأربعاء", d4: "الخميس", d5: "الجمعة", d6: "السبت", d7: "الأحد" },
  weekday: "{d}",
  weekdays: "{days}",
  enumSep: "، ",
  qCol: { name: "قائمة الانتظار", code: "الرمز", orderBy: "الترتيب", claimTimeout: "مهلة الاستلام", actions: "الإجراءات" },
  cal: {
    summary: "المنطقة الزمنية {tz} · {days} {open}–{close}",
    holidays: " · العطلات {n} يوم ({list}{more})",
    more: "…",
    holidayDefault: "عطلة",
  },
  empty: {
    queue: "لا توجد قوائم انتظار بعد — المراجعة بالاستلام تتطلب إنشاء قائمة أولًا",
  },
  drawer: {
    calEdit: "تعديل تقويم العمل", qNew: "إضافة قائمة انتظار", qEdit: "تعديل قائمة الانتظار",
  },
  saved: {
    cal: "تم حفظ تقويم العمل",
    queue: "تم حفظ قائمة الانتظار",
  },
  calField: {
    name: "الاسم",
    timeZone: "المنطقة الزمنية",
    timeZoneHelp: "منطقة زمنية وفق IANA، مثل Asia/Dubai",
    weekdays: "أيام العمل",
    open: "وقت بدء العمل",
    openMsg: "HH:mm، مثل 10:00",
    close: "وقت انتهاء العمل",
    closeMsg: "HH:mm، مثل 22:00",
    holidays: "العطلات",
    holidaysHelp: "واحدة في كل سطر: التاريخ ثم الاسم، مثل «2026-12-02 اليوم الوطني»",
  },
  qField: {
    code: "الرمز",
    codeHelp: "أحرف لاتينية صغيرة وأرقام وشرطات؛ لا يمكن تغييره بعد الإنشاء",
    name: "الاسم",
    orderBy: "الترتيب",
    claimTimeoutMin: "مهلة الاستلام (بالدقائق)",
    claimTimeoutHelp: "إذا لم يُتخذ قرار خلال المهلة بعد الاستلام، يُعاد تلقائيًا إلى القائمة مع تسجيل ذلك",
  },
} satisfies M;

export const settings = { zh, en, ar };
