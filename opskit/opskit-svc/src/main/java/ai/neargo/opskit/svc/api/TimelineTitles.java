package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.ticket.api.model.TimelineItem;
import java.util.Map;
import java.util.function.Function;

/** 时间线标题（服务按语言给好，21/06 §七）。名称类取值（迁移名 · 状态名 · 人名）由调用方传入已选好语言的文本。 */
final class TimelineTitles {

    private TimelineTitles() {
    }

    private static final Map<String, Map<String, String>> T = Map.ofEntries(
            Map.entry("CREATED", Map.of("zh", "创建工单", "en", "Ticket created", "ar", "تم إنشاء التذكرة")),
            Map.entry("claimed", Map.of("zh", "领取", "en", "Claimed", "ar", "تم الاستلام")),
            Map.entry("released", Map.of("zh", "放回队列", "en", "Released to queue", "ar", "أُعيدت إلى الطابور")),
            Map.entry("claim_timeout", Map.of("zh", "领取超时，自动放回", "en", "Claim timed out, released", "ar", "انتهت مهلة الاستلام")),
            Map.entry("assigned", Map.of("zh", "派给 {to}", "en", "Assigned to {to}", "ar", "أُسندت إلى {to}")),
            Map.entry("reassigned", Map.of("zh", "改派给 {to}", "en", "Reassigned to {to}", "ar", "أُعيد الإسناد إلى {to}")),
            Map.entry("pooled", Map.of("zh", "退回抢单池", "en", "Returned to pool", "ar", "أُعيدت إلى المجمع")),
            Map.entry("submitted", Map.of("zh", "提交审批", "en", "Submitted for approval", "ar", "أُرسلت للاعتماد")),
            Map.entry("approve", Map.of("zh", "审批通过", "en", "Approved a step", "ar", "تمت الموافقة على خطوة")),
            Map.entry("reject", Map.of("zh", "审批驳回", "en", "Rejected", "ar", "مرفوض")),
            Map.entry("completed", Map.of("zh", "审批办结：{to}", "en", "Approval finished: {to}", "ar", "انتهى الاعتماد: {to}")),
            Map.entry("SLA", Map.of("zh", "时限 {metric}：{state}", "en", "SLA {metric}: {state}", "ar", "المهلة {metric}: {state}")),
            Map.entry("COMMENT.PUBLIC", Map.of("zh", "评论", "en", "Comment", "ar", "تعليق")),
            Map.entry("COMMENT.INTERNAL", Map.of("zh", "内部备注", "en", "Internal note", "ar", "ملاحظة داخلية")),
            Map.entry("DEDUPE", Map.of("zh", "重复报修已附加到本单", "en", "Duplicate report attached", "ar", "أُرفق بلاغ مكرر")),
            Map.entry("MERGE", Map.of("zh", "合并", "en", "Merged", "ar", "دمج")),
            Map.entry("ESCALATED", Map.of("zh", "升级", "en", "Escalated", "ar", "تصعيد")));

    private static final Map<String, Map<String, String>> SLA_STATES = Map.of(
            "RUNNING", Map.of("zh", "计时中", "en", "running", "ar", "قيد العد"),
            "WARNING", Map.of("zh", "即将到期", "en", "due soon", "ar", "قارب الانتهاء"),
            "PAUSED", Map.of("zh", "已暂停", "en", "paused", "ar", "متوقف"),
            "BREACHED", Map.of("zh", "已逾期", "en", "breached", "ar", "متجاوز"),
            "MET", Map.of("zh", "按时完成", "en", "met", "ar", "أُنجز في الوقت"),
            "CANCELLED", Map.of("zh", "已取消", "en", "cancelled", "ar", "أُلغي"));

    /**
     * @param person  主体（member:x）→ 显示名
     * @param metric  指标代号 → 指标名
     */
    static String title(TimelineItem i, String locale, Function<String, String> person, Function<String, String> metric,
            Function<ai.neargo.opskit.commons.i18n.LocalizedText, String> text) {
        return switch (i.kind()) {
            case "CREATED" -> pick("CREATED", locale);
            case "TRANSITION" -> {
                String name = i.actionName() == null ? i.action() : text.apply(i.actionName());
                String from = i.fromName() == null ? i.from() : text.apply(i.fromName());
                String to = i.toName() == null ? i.to() : text.apply(i.toName());
                yield name + ("ar".equals(locale) ? ": " : "zh".equals(locale) ? "：" : ": ") + from + " → " + to;
            }
            case "ASSIGNED" -> pick("created".equals(i.action()) ? "assigned" : i.action(), locale).replace("{to}", String.valueOf(person.apply(i.to())));
            case "APPROVAL" -> pick(i.action(), locale).replace("{to}", String.valueOf(i.to()));
            case "SLA" -> pick("SLA", locale).replace("{metric}", metric.apply(i.action()))
                    .replace("{state}", SLA_STATES.getOrDefault(i.to(), Map.of()).getOrDefault(locale, String.valueOf(i.to())));
            case "COMMENT" -> pick("COMMENT." + i.visibility(), locale);
            default -> pick(i.kind(), locale);
        };
    }

    private static String pick(String key, String locale) {
        Map<String, String> m = T.get(key);
        return m == null ? key : m.getOrDefault(locale, m.get("en"));
    }
}
