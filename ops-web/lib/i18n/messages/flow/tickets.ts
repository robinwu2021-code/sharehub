// 工单工作台（/flow/tickets/）。页面名、分隔符取 common；统计卡 label、工单类型名等是服务给的数据，不在此。
// ar 为机器初译，needs-review。
const zh = {
  desc: "统计卡可点，点进即筛好的列表。SLA 色条：绿 正常 · 黄 将超时 · 红 已超时 · 灰 暂停。",
  col: {
    no: "单号", type: "类型", titleSubject: "标题 / 对象", priority: "优先级", status: "状态",
    assignee: "受理方", sla: "SLA", source: "来源", actions: "操作",
  },
  unassigned: "未派 · 抢单池",
  done: {
    NUDGE: "已催办受理方", ESCALATE: "已升级给平台", TAKEOVER: "已接管，派给了你", REVIEW: "复核通过，工单已关闭",
    other: "已完成",
  },
  readOnly: { what: "派单与改派", note: "不能勾选批量改派；单张工单的办理动作以各行按钮为准" },
  searchPlaceholder: "搜单号 / 标题 / 对象 / 受理方",
  filter: { type: "工单类型", typeAll: "全部类型", assignee: "受理方", assigneeAll: "受理方：全部" },
  empty: { filtered: "按当前筛选没有工单 —— 换个条件，或点已选中的统计卡取消筛选", none: "还没有工单" },
  create: {
    btn: "新建工单",
    title: "新建工单",
    done: "已建单 {no}，已进抢单池",
    attached: "去重键命中未结工单 {no}，已并入它（没有另开新单）",
    field: {
      typeCode: "工单类型", title: "标题", titleHelp: "一句话说清问题，如「机柜离线」", priority: "优先级",
      subjectType: "业务对象", subjectRef: "对象编号", subjectRefHelp: "如 CAB1001、ST0311、CU0148；不涉及具体对象可不填",
      description: "描述", dedupeKey: "去重键", dedupeKeyHelp: "同一件事重复报时填同一个键（如告警号），会并入未结的那张单",
    },
    subject: { none: "不涉及", cabinet: "柜机", site: "站点", user: "用户" },
    priority: { URGENT: "紧急", HIGH: "高", NORMAL: "普通", LOW: "低" },
  },
  reassign: {
    btn: "批量改派",
    pickHint: "勾选要改派的工单",
    cancel: "取消",
    title: "批量改派 {n} 张工单",
    done: "已改派 {n} 张工单",
    partial: "已改派 {ok} 张；{n} 张没改：{list}",
    to: "改派给",
    reason: "原因",
    reasonHelp: "会记入每张工单的时间线；例如「Musa 休假，片区改由 Omar 负责」",
  },
};
type M = typeof zh;

const en = {
  desc: "Click a stat card to open the list already filtered. SLA bar: green on track · yellow due soon · red overdue · grey paused.",
  col: {
    no: "No.", type: "Type", titleSubject: "Title / object", priority: "Priority", status: "Status",
    assignee: "Assignee", sla: "SLA", source: "Source", actions: "Actions",
  },
  unassigned: "Unassigned · in pool",
  done: {
    NUDGE: "Assignee nudged", ESCALATE: "Escalated to the platform", TAKEOVER: "Taken over — now assigned to you", REVIEW: "Review passed; ticket closed",
    other: "Done",
  },
  readOnly: { what: "dispatching and reassigning", note: "you cannot select tickets for bulk reassign; per-ticket actions follow the buttons on each row" },
  searchPlaceholder: "Search no. / title / object / assignee",
  filter: { type: "Ticket type", typeAll: "All types", assignee: "Assignee", assigneeAll: "Assignee: all" },
  empty: { filtered: "No tickets match the current filters — change them, or click the selected stat card to clear it", none: "No tickets yet" },
  create: {
    btn: "New ticket",
    title: "New ticket",
    done: "Ticket {no} created and placed in the pool",
    attached: "Dedupe key matched open ticket {no}; merged into it (no new ticket)",
    field: {
      typeCode: "Ticket type", title: "Title", titleHelp: "One line describing the problem, e.g. \"Cabinet offline\"", priority: "Priority",
      subjectType: "Business object", subjectRef: "Object number", subjectRefHelp: "e.g. CAB1001, ST0311, CU0148; leave empty if none",
      description: "Description", dedupeKey: "Dedupe key", dedupeKeyHelp: "Use the same key for repeat reports of the same issue (e.g. alarm no.); it merges into the open ticket",
    },
    subject: { none: "None", cabinet: "Cabinet", site: "Site", user: "User" },
    priority: { URGENT: "Urgent", HIGH: "High", NORMAL: "Normal", LOW: "Low" },
  },
  reassign: {
    btn: "Bulk reassign",
    pickHint: "Select the tickets to reassign",
    cancel: "Cancel",
    title: "Reassign {n} tickets",
    done: "Reassigned {n} tickets",
    partial: "Reassigned {ok}; {n} not reassigned: {list}",
    to: "Reassign to",
    reason: "Reason",
    reasonHelp: "Recorded in each ticket's timeline, e.g. \"Musa is on leave; Omar covers the area\"",
  },
} satisfies M;

const ar = {
  desc: "بطاقات الإحصاء قابلة للنقر وتفتح القائمة مُصفّاة. شريط SLA: أخضر طبيعي · أصفر قارب على التأخر · أحمر متأخر · رمادي متوقف.",
  col: {
    no: "الرقم", type: "النوع", titleSubject: "العنوان / الكائن", priority: "الأولوية", status: "الحالة",
    assignee: "المسؤول", sla: "SLA", source: "المصدر", actions: "الإجراءات",
  },
  unassigned: "غير مُسند · في المجمّع",
  done: {
    NUDGE: "تم تذكير المسؤول", ESCALATE: "تم التصعيد إلى المنصة", TAKEOVER: "تم الاستلام وإسنادها إليك", REVIEW: "اجتازت المراجعة وأُغلقت التذكرة",
    other: "تم",
  },
  readOnly: { what: "الإسناد وإعادة الإسناد", note: "لا يمكنك تحديد التذاكر لإعادة الإسناد الجماعية؛ إجراءات كل تذكرة حسب أزرار صفها" },
  searchPlaceholder: "ابحث بالرقم / العنوان / الكائن / المسؤول",
  filter: { type: "نوع التذكرة", typeAll: "كل الأنواع", assignee: "المسؤول", assigneeAll: "المسؤول: الكل" },
  empty: { filtered: "لا توجد تذاكر تطابق عوامل التصفية الحالية — غيّرها، أو انقر بطاقة الإحصاء المحددة لإلغائها", none: "لا توجد تذاكر بعد" },
  create: {
    btn: "تذكرة جديدة",
    title: "تذكرة جديدة",
    done: "أُنشئت التذكرة {no} ووُضعت في المجمّع",
    attached: "طابق مفتاح التكرار التذكرة المفتوحة {no}؛ دُمجت فيها (لا تذكرة جديدة)",
    field: {
      typeCode: "نوع التذكرة", title: "العنوان", titleHelp: "سطر واحد يصف المشكلة، مثل «خزانة غير متصلة»", priority: "الأولوية",
      subjectType: "كائن العمل", subjectRef: "رقم الكائن", subjectRefHelp: "مثل CAB1001 أو ST0311 أو CU0148؛ اتركه فارغًا إن لم يوجد",
      description: "الوصف", dedupeKey: "مفتاح التكرار", dedupeKeyHelp: "استخدم المفتاح نفسه للبلاغات المتكررة عن المشكلة نفسها (مثل رقم التنبيه)؛ تُدمج في التذكرة المفتوحة",
    },
    subject: { none: "لا يوجد", cabinet: "خزانة", site: "موقع", user: "مستخدم" },
    priority: { URGENT: "عاجل", HIGH: "مرتفع", NORMAL: "عادي", LOW: "منخفض" },
  },
  reassign: {
    btn: "إعادة إسناد جماعية",
    pickHint: "حدّد التذاكر المراد إعادة إسنادها",
    cancel: "إلغاء",
    title: "إعادة إسناد {n} تذكرة",
    done: "تمت إعادة إسناد {n} تذكرة",
    partial: "أُعيد إسناد {ok}؛ لم يُعَد إسناد {n}: {list}",
    to: "إعادة الإسناد إلى",
    reason: "السبب",
    reasonHelp: "يُسجَّل في الخط الزمني لكل تذكرة، مثل «Musa في إجازة، ويتولى Omar المنطقة»",
  },
} satisfies M;

export const tickets = { zh, en, ar };
