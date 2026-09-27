// 工单详情（/flow/tickets/detail/）。ar 为机器初译，needs-review。
const zh = {
  field: {
    reason: { label: "原因", help: "会记入工单时间线" },
    to: { label: "改派给", pool: "退回抢单池（未派）" },
  },
  done: "已{action}",
  assign: {
    pickTarget: "请选择改派对象",
    pooled: "已退回抢单池",
    reassigned: "已改派",
    btn: "改派 / 回抢单池",
    title: "改派：{no}",
  },
  missingNo: "缺少工单号：从工单工作台或待办中心进入",
  subtitle: { type: "{type}工单", source: "来源 {src}", dedupe: "（去重键 {key}，重复{source}已并入 {n} 次）" },
  deviceProfile: "设备档案 ↗",
  section: { subject: "对象", timeline: "时间线", actions: "动作", actionsHint: "（仅当前状态可走的）", note: "备注" },
  timelineEmpty: "还没有流转记录",
  dueBefore: "{t} 前",
  closed: { DONE: "工单已关闭，没有可走的动作。", CANCELLED: "工单已撤销，没有可走的动作。" },
  closedWith: { DONE: "工单已关闭：{r}，没有可走的动作。", CANCELLED: "工单已撤销：{r}，没有可走的动作。" },
  needsReason: "{action}（需填原因）",
  note: {
    placeholder: "添加备注：会进入时间线，受理方与运营都能看到",
    submit: "提交备注",
    submitted: "备注已提交",
  },
  readOnly: { what: "工单办理", note: "不能添加备注" },
  reasonTitle: "{action}：{no}",
};
type M = typeof zh;

const en = {
  field: {
    reason: { label: "Reason", help: "Recorded in the ticket timeline" },
    to: { label: "Reassign to", pool: "Return to pool (unassigned)" },
  },
  done: "{action}: done",
  assign: {
    pickTarget: "Choose who to reassign to",
    pooled: "Returned to the pool",
    reassigned: "Reassigned",
    btn: "Reassign / return to pool",
    title: "Reassign: {no}",
  },
  missingNo: "Missing ticket number — open this from the Ticket desk or To-do",
  subtitle: { type: "{type} ticket", source: "Source {src}", dedupe: " (dedupe key {key}; {n} duplicate {source} merged in)" },
  deviceProfile: "Device profile ↗",
  section: { subject: "Object", timeline: "Timeline", actions: "Actions", actionsHint: "(only those available in the current status)", note: "Notes" },
  timelineEmpty: "No activity yet",
  dueBefore: "by {t}",
  closed: { DONE: "Ticket closed; no actions available.", CANCELLED: "Ticket cancelled; no actions available." },
  closedWith: { DONE: "Ticket closed: {r}; no actions available.", CANCELLED: "Ticket cancelled: {r}; no actions available." },
  needsReason: "{action} (reason required)",
  note: {
    placeholder: "Add a note — it goes into the timeline and is visible to both the handler and operations",
    submit: "Add note",
    submitted: "Note added",
  },
  readOnly: { what: "ticket handling", note: "you cannot add notes" },
  reasonTitle: "{action}: {no}",
} satisfies M;

const ar = {
  field: {
    reason: { label: "السبب", help: "يُسجَّل في الخط الزمني للتذكرة" },
    to: { label: "إعادة الإسناد إلى", pool: "إرجاع إلى المجمّع (غير مسندة)" },
  },
  done: "تم: {action}",
  assign: {
    pickTarget: "يُرجى اختيار الجهة المراد الإسناد إليها",
    pooled: "أُعيدت إلى المجمّع",
    reassigned: "تمت إعادة الإسناد",
    btn: "إعادة الإسناد / الإرجاع إلى المجمّع",
    title: "إعادة إسناد: {no}",
  },
  missingNo: "رقم التذكرة مفقود — افتحها من مكتب التذاكر أو من المهام",
  subtitle: { type: "تذكرة {type}", source: "المصدر {src}", dedupe: " (مفتاح إزالة التكرار {key}؛ دُمج {source} المكرر {n} مرة)" },
  deviceProfile: "ملف الجهاز ↗",
  section: { subject: "الكائن", timeline: "الخط الزمني", actions: "الإجراءات", actionsHint: "(المتاحة في الحالة الحالية فقط)", note: "الملاحظات" },
  timelineEmpty: "لا توجد سجلات بعد",
  dueBefore: "قبل {t}",
  closed: { DONE: "أُغلقت التذكرة، ولا توجد إجراءات متاحة.", CANCELLED: "أُلغيت التذكرة، ولا توجد إجراءات متاحة." },
  closedWith: { DONE: "أُغلقت التذكرة: {r}، ولا توجد إجراءات متاحة.", CANCELLED: "أُلغيت التذكرة: {r}، ولا توجد إجراءات متاحة." },
  needsReason: "{action} (السبب مطلوب)",
  note: {
    placeholder: "أضف ملاحظة — ستظهر في الخط الزمني ويراها كلٌّ من المعالِج وفريق التشغيل",
    submit: "إرسال الملاحظة",
    submitted: "تم إرسال الملاحظة",
  },
  readOnly: { what: "معالجة التذاكر", note: "لا يمكنك إضافة ملاحظات" },
  reasonTitle: "{action}: {no}",
} satisfies M;

export const ticketDetail = { zh, en, ar };
