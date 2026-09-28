// 审核队列（/flow/queue/）。ar 为机器初译，needs-review。
const zh = {
  desc: "领取后才能决定；领取超时 30 分钟自动放回并留痕。",
  tab: { unclaimed: "待领 {n}" },
  col: { subject: "对象", priority: "优先级", wait: "等待", evidence: "证据", status: "状态", actions: "操作" },
  status: { reviewing: "{name} 审核中", unclaimed: "待领" },
  btn: { approve: "通过", reject: "驳回", release: "放回", claim: "领取", work: "去工单办理" },
  toast: { claimed: "已领取，30 分钟内完成审核", released: "已放回队列", approved: "已通过", rejected: "已驳回" },
  mine: {
    title: "我在审（{n}）",
    countdown: "领取倒计时 {t} 后自动放回",
    empty: "你手上没有在审的 —— 从下面的队列里领一项",
  },
  queue: { title: "队列", sortHint: "按优先级 → 等待时长排序", empty: "这个队列里没有待审的了" },
  reject: {
    title: "驳回：{title}",
    reason: { label: "驳回原因", help: "申请人会看到这段原因，写清楚要补什么" },
  },
};
type M = typeof zh;

const en = {
  desc: "Claim an item before deciding on it; claims expire after 30 minutes and go back to the queue, with a record kept.",
  tab: { unclaimed: "{n} unclaimed" },
  col: { subject: "Object", priority: "Priority", wait: "Waiting", evidence: "Evidence", status: "Status", actions: "Actions" },
  status: { reviewing: "{name} reviewing", unclaimed: "Unclaimed" },
  btn: { approve: "Approve", reject: "Reject", release: "Release", claim: "Claim", work: "Work on ticket" },
  toast: { claimed: "Claimed — finish the review within 30 minutes", released: "Released back to the queue", approved: "Approved", rejected: "Rejected" },
  mine: {
    title: "My reviews ({n})",
    countdown: "Claim expires in {t}, then returns to the queue",
    empty: "You have nothing under review — claim one from the queue below",
  },
  queue: { title: "Queue", sortHint: "Sorted by priority → waiting time", empty: "Nothing left to review in this queue" },
  reject: {
    title: "Reject: {title}",
    reason: { label: "Rejection reason", help: "The applicant will see this — say clearly what needs to be added" },
  },
} satisfies M;

const ar = {
  desc: "يجب الاستلام قبل اتخاذ القرار؛ ينتهي الاستلام بعد 30 دقيقة ويُعاد العنصر إلى الطابور مع تسجيل ذلك.",
  tab: { unclaimed: "{n} غير مستلَم" },
  col: { subject: "الكائن", priority: "الأولوية", wait: "الانتظار", evidence: "الأدلة", status: "الحالة", actions: "الإجراءات" },
  status: { reviewing: "{name} يراجع", unclaimed: "غير مستلَم" },
  btn: { approve: "موافقة", reject: "رفض", release: "إرجاع", claim: "استلام", work: "العمل على التذكرة" },
  toast: { claimed: "تم الاستلام، أكمل المراجعة خلال 30 دقيقة", released: "أُعيد إلى الطابور", approved: "تمت الموافقة", rejected: "تم الرفض" },
  mine: {
    title: "قيد مراجعتي ({n})",
    countdown: "ينتهي الاستلام بعد {t} ويُعاد تلقائيًا",
    empty: "لا يوجد لديك ما تراجعه — استلم عنصرًا من الطابور أدناه",
  },
  queue: { title: "الطابور", sortHint: "مرتب حسب الأولوية ← مدة الانتظار", empty: "لا يوجد ما يُراجع في هذا الطابور" },
  reject: {
    title: "رفض: {title}",
    reason: { label: "سبب الرفض", help: "سيرى مقدّم الطلب هذا السبب؛ وضّح ما يجب استكماله" },
  },
} satisfies M;

export const queue = { zh, en, ar };
