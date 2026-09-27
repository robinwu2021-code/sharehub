// 审批列表（/flow/approvals/）。状态名走 common.approvalStatus.*。ar 为机器初译，needs-review。
const zh = {
  desc: "全部审批单（不只「待我审」）；按数据范围过滤。筛选条件即查询接口参数，一一对应。",
  tab: { all: "全部" },
  since: { d7: "提交时间：近 7 天", d30: "提交时间：近 30 天" },
  col: {
    approvalNo: "审批单", process: "流程", subject: "业务对象", status: "状态", stepRound: "当前步 / 轮次",
    requestedBy: "提交人", requestedAt: "提交时间", waited: "已等待",
  },
  round: "第 {n} 轮",
  overdueTitle: "当前步已超过步骤时限",
  took: "耗时 {d}",
  search: "搜单号 / 对象编号 / 标题",
  filterAll: { process: "流程：全部", requestedBy: "提交人：全部", since: "提交时间：不限" },
  empty: {
    filtered: "按当前筛选没有审批单 —— 换个条件试试",
    pending: "没有待审的审批单",
    status: "这个状态下没有审批单",
  },
  footer: "「已等待」= 当前步开始至今；已结单显示总耗时。点单号进详情。",
};
type M = typeof zh;

const en = {
  desc: "All approvals (not just those waiting on you), filtered by your data scope. Each filter maps one-to-one to a query API parameter.",
  tab: { all: "All" },
  since: { d7: "Submitted: last 7 days", d30: "Submitted: last 30 days" },
  col: {
    approvalNo: "Approval", process: "Flow", subject: "Business object", status: "Status", stepRound: "Current step / round",
    requestedBy: "Submitted by", requestedAt: "Submitted at", waited: "Waiting",
  },
  round: "Round {n}",
  overdueTitle: "The current step is past its time limit",
  took: "Took {d}",
  search: "Search approval no. / object no. / title",
  filterAll: { process: "Flow: all", requestedBy: "Submitted by: anyone", since: "Submitted: any time" },
  empty: {
    filtered: "No approvals match the current filters — try different ones",
    pending: "No approvals pending review",
    status: "No approvals in this status",
  },
  footer: "\"Waiting\" = time since the current step started; closed approvals show the total time taken. Click an approval number for details.",
} satisfies M;

const ar = {
  desc: "جميع طلبات الموافقة (وليس فقط ما ينتظر مراجعتك)، مُصفّاة حسب نطاق بياناتك. كل عامل تصفية يقابل معاملًا واحدًا في واجهة الاستعلام.",
  tab: { all: "الكل" },
  since: { d7: "وقت التقديم: آخر 7 أيام", d30: "وقت التقديم: آخر 30 يومًا" },
  col: {
    approvalNo: "طلب الموافقة", process: "المسار", subject: "كائن العمل", status: "الحالة", stepRound: "الخطوة الحالية / الجولة",
    requestedBy: "مقدّم الطلب", requestedAt: "وقت التقديم", waited: "مدة الانتظار",
  },
  round: "الجولة {n}",
  overdueTitle: "تجاوزت الخطوة الحالية مهلتها",
  took: "استغرق {d}",
  search: "ابحث برقم الطلب / رقم الكائن / العنوان",
  filterAll: { process: "المسار: الكل", requestedBy: "مقدّم الطلب: الكل", since: "وقت التقديم: أي وقت" },
  empty: {
    filtered: "لا توجد طلبات موافقة تطابق عوامل التصفية الحالية — جرّب شروطًا أخرى",
    pending: "لا توجد طلبات موافقة بانتظار المراجعة",
    status: "لا توجد طلبات موافقة بهذه الحالة",
  },
  footer: "«مدة الانتظار» = الوقت منذ بدء الخطوة الحالية؛ الطلبات المنتهية تعرض المدة الإجمالية. انقر رقم الطلب لعرض التفاصيل.",
} satisfies M;

export const approvals = { zh, en, ar };
