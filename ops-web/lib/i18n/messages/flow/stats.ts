// 流程统计（/flow/stats/）。ar 为机器初译，needs-review。
const zh = {
  desc: "各流程在所选时间段内发起的实例：总量、未结、平均用时与审批结果。口径与流程实例列表一致。",
  filter: { process: "流程", processAll: "全部流程", period: "时间段", d7: "近 7 天", d30: "近 30 天", d90: "近 90 天", all: "全部" },
  card: { total: "发起", open: "未结", closed: "已结", avg: "平均用时" },
  col: { process: "流程", total: "发起", open: "未结", byCategory: "状态分布", avg: "平均用时", approvals: "审批结果" },
  noAvg: "暂无已结",
  toList: "看实例",
  empty: "所选时间段内没有发起过流程实例",
};
type M = typeof zh;
const en = {
  desc: "Instances started in the selected period, per process: volume, still open, average duration and approval outcomes. Same basis as the instance list.",
  filter: { process: "Process", processAll: "All processes", period: "Period", d7: "Last 7 days", d30: "Last 30 days", d90: "Last 90 days", all: "All time" },
  card: { total: "Started", open: "Open", closed: "Closed", avg: "Avg. duration" },
  col: { process: "Process", total: "Started", open: "Open", byCategory: "By status", avg: "Avg. duration", approvals: "Approval outcomes" },
  noAvg: "None closed yet",
  toList: "View instances",
  empty: "No process instances started in this period",
} satisfies M;
const ar = {
  desc: "المثيلات التي بدأت في الفترة المحددة لكل مسار: العدد، المفتوح، متوسط المدة ونتائج الموافقات. بنفس أساس قائمة المثيلات.",
  filter: { process: "المسار", processAll: "كل المسارات", period: "الفترة", d7: "آخر 7 أيام", d30: "آخر 30 يومًا", d90: "آخر 90 يومًا", all: "كل الوقت" },
  card: { total: "بدأت", open: "مفتوحة", closed: "مغلقة", avg: "متوسط المدة" },
  col: { process: "المسار", total: "بدأت", open: "مفتوحة", byCategory: "حسب الحالة", avg: "متوسط المدة", approvals: "نتائج الموافقات" },
  noAvg: "لا مغلق بعد",
  toList: "عرض المثيلات",
  empty: "لم تبدأ مثيلات في هذه الفترة",
} satisfies M;
export const stats = { zh, en, ar };
