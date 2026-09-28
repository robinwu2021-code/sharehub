// 待办中心（/flow/todo/）。ar 为机器初译，needs-review。
const zh = {
  desc: "审批与工单在同一张列表；只显示你够资格办、且在你数据范围内的。",
  tab: { all: "全部", process: "审批", ticket: "工单", pool: "可抢单" },
  empty: {
    all: "你现在没有待办 —— 新的审批与派给你的工单会出现在这里",
    process: "没有等你审批的单",
    ticket: "没有待你办理或可以领取的工单",
    pool: "抢单池里暂时没有你够资格领的单",
  },
  col: { kind: "类型", title: "标题", subject: "业务对象", priority: "优先级", sla: "剩余时限" },
  claim: "抢单",
  claimed: "已领取 {no}，派给了你",
  readOnly: { what: "工单办理", note: "看得到抢单池，但不能抢单" },
  footer: { before: "没有待办 ≠ 没有工单 —— 全部工单去", link: "工单工作台", after: "；已办结的在各单详情的时间线里。" },
};
type M = typeof zh;

const en = {
  desc: "Approvals and tickets in one list — only what you are eligible to handle and within your data scope.",
  tab: { all: "All", process: "Approvals", ticket: "Tickets", pool: "Claimable" },
  empty: {
    all: "Nothing to do right now — new approvals and tickets assigned to you will show up here",
    process: "No approvals waiting for you",
    ticket: "No tickets for you to work on or claim",
    pool: "Nothing in the pool you are eligible to claim right now",
  },
  col: { kind: "Type", title: "Title", subject: "Business object", priority: "Priority", sla: "Time left" },
  claim: "Claim",
  claimed: "Claimed {no} — it is now assigned to you",
  readOnly: { what: "ticket handling", note: "you can see the pool but cannot claim" },
  footer: { before: "No to-dos ≠ no tickets — see all tickets in the", link: "Ticket desk", after: "; closed ones are in each ticket's timeline." },
} satisfies M;

const ar = {
  desc: "الموافقات والتذاكر في قائمة واحدة؛ يظهر فقط ما يحق لك معالجته وضمن نطاق بياناتك.",
  tab: { all: "الكل", process: "الموافقات", ticket: "التذاكر", pool: "قابل للاستلام" },
  empty: {
    all: "لا توجد مهام الآن — ستظهر هنا الموافقات الجديدة والتذاكر المسندة إليك",
    process: "لا توجد موافقات بانتظارك",
    ticket: "لا توجد تذاكر لتعمل عليها أو تستلمها",
    pool: "لا يوجد في المجمّع حاليًا ما يحق لك استلامه",
  },
  col: { kind: "النوع", title: "العنوان", subject: "كائن العمل", priority: "الأولوية", sla: "الوقت المتبقي" },
  claim: "استلام",
  claimed: "تم استلام {no} وإسناده إليك",
  readOnly: { what: "معالجة التذاكر", note: "يمكنك رؤية المجمّع لكن لا يمكنك الاستلام" },
  footer: { before: "لا مهام ≠ لا تذاكر — جميع التذاكر في", link: "مكتب التذاكر", after: "؛ والمغلقة في الخط الزمني لكل تذكرة." },
} satisfies M;

export const todo = { zh, en, ar };
