package ai.neargo.opskit.process.core.domain.definition;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalRules;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审批模板 → 审批类流程（21/02 §6.4a）。纯函数：同一份模板永远编译出同一份流程，于是产物的版本哈希也不变（重复发布幂等）。
 *
 * <pre>
 * 草稿 ─submit→ 审批中[审批门：steps 原样] ─approved→ 已通过
 *                                         ─rejected→ 已驳回
 *                                         ─withdrawn→ 草稿
 * </pre>
 */
public final class ApprovalTemplateCompiler {

    public static final String DRAFT = "draft";
    public static final String REVIEWING = "reviewing";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final int MAX_STEPS = 20;
    public static final int MAX_DUE_HOURS = 24 * 365;

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> OPS = Set.of("TRUE", "EQ", "IN");

    private ApprovalTemplateCompiler() {
    }

    /** 按模板字段给逐条错误；空列表 = 合法。 */
    public static List<FieldError> validate(ApprovalTemplate t) {
        List<FieldError> e = new ArrayList<>();
        if (t.code() == null || !CODE.matcher(t.code()).matches()) {
            e.add(new FieldError("code", "opskit.validation.code_format"));
        }
        if (blank(t.name())) {
            e.add(new FieldError("name", "opskit.validation.required"));
        }
        if (t.steps().isEmpty()) {
            e.add(new FieldError("steps", "opskit.validation.required"));
        } else if (t.steps().size() > MAX_STEPS) {
            e.add(new FieldError("steps", "opskit.template.too_many_steps"));
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < t.steps().size(); i++) {
            ApprovalTemplate.Step s = t.steps().get(i);
            String p = "steps[" + i + "]";
            if (s == null) {
                e.add(new FieldError(p, "opskit.validation.required"));
                continue;
            }
            if (s.code() == null || !CODE.matcher(s.code()).matches()) {
                e.add(new FieldError(p + ".code", "opskit.validation.code_format"));
            } else if (!codes.add(s.code())) {
                e.add(new FieldError(p + ".code", "opskit.validation.duplicate"));
            }
            if (blank(s.name())) {
                e.add(new FieldError(p + ".name", "opskit.validation.required"));
            }
            ApprovalTemplate.Approver a = s.approver();
            if (a == null || a.kind() == null) {
                e.add(new FieldError(p + ".approver.kind", "opskit.validation.required"));
            } else if (a.kind() == ApproverKind.PARTICIPANT) {
                e.add(new FieldError(p + ".approver.kind", "opskit.validation.one_of"));
            } else {
                List<String> vs = a.values().stream().filter(v -> v != null && !v.isBlank()).toList();
                boolean ok = a.kind() == ApproverKind.MEMBER ? !vs.isEmpty() && vs.size() == a.values().size() : vs.size() == 1 && a.values().size() == 1;
                if (!ok) {
                    e.add(new FieldError(p + ".approver.value", "opskit.validation.required"));
                }
            }
            if (!"ANY".equals(s.mode()) && !"ALL".equals(s.mode())) {
                e.add(new FieldError(p + ".mode", "opskit.validation.one_of"));
            } else if ("ALL".equals(s.mode()) && a != null && a.kind() != null && !a.kind().enumerable()) {
                // 会签要为每个人建任务，角色 / 宿主权限列不出全部人选（21/02 §6.4a）
                e.add(new FieldError(p + ".mode", "opskit.template.countersign_not_enumerable"));
            }
            if (s.when() != null) {
                if (s.when().field() == null || s.when().field().isBlank()) {
                    e.add(new FieldError(p + ".when.field", "opskit.validation.required"));
                }
                if (!OPS.contains(s.when().op())) {
                    e.add(new FieldError(p + ".when.op", "opskit.validation.one_of"));
                } else if (!"TRUE".equals(s.when().op()) && (s.when().value() == null || s.when().value().isBlank())) {
                    e.add(new FieldError(p + ".when.value", "opskit.validation.required"));
                }
            }
            if (s.dueHours() != null && (s.dueHours() < 1 || s.dueHours() > MAX_DUE_HOURS)) {
                e.add(new FieldError(p + ".dueHours", "opskit.validation.out_of_range"));
            }
        }
        validateForm(t.fields(), e);
        return e;
    }

    public static final Set<String> FIELD_TYPES = Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT");
    private static final int MAX_FIELDS = 30;

    private static void validateForm(List<ApprovalTemplate.FormField> fields, List<FieldError> e) {
        if (fields.size() > MAX_FIELDS) {
            e.add(new FieldError("fields", "opskit.validation.too_many"));
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            ApprovalTemplate.FormField f = fields.get(i);
            String p = "fields[" + i + "]";
            if (f == null) {
                e.add(new FieldError(p, "opskit.validation.required"));
                continue;
            }
            if (f.code() == null || !CODE.matcher(f.code()).matches()) {
                e.add(new FieldError(p + ".code", "opskit.validation.code_format"));
            } else if (!codes.add(f.code())) {
                e.add(new FieldError(p + ".code", "opskit.validation.duplicate"));
            }
            if (blank(f.name())) {
                e.add(new FieldError(p + ".name", "opskit.validation.required"));
            }
            if (!FIELD_TYPES.contains(f.type())) {
                e.add(new FieldError(p + ".type", "opskit.validation.one_of"));
            } else if ("SELECT".equals(f.type())) {
                if (f.options().isEmpty()) {
                    e.add(new FieldError(p + ".options", "opskit.validation.required"));
                }
                Set<String> oc = new HashSet<>();
                for (int j = 0; j < f.options().size(); j++) {
                    ApprovalTemplate.Option o = f.options().get(j);
                    if (o == null || o.code() == null || o.code().isBlank()) {
                        e.add(new FieldError(p + ".options[" + j + "].code", "opskit.validation.required"));
                    } else if (!oc.add(o.code())) {
                        e.add(new FieldError(p + ".options[" + j + "].code", "opskit.validation.duplicate"));
                    }
                }
            } else if (!f.options().isEmpty()) {
                e.add(new FieldError(p + ".options", "opskit.validation.invalid"));
            }
        }
    }

    /**
     * 发起时按表单校验填的值：必填 · 类型 · 选项。错误路径 {@code fields.<代号>}。表单外的键原样放行（宿主可能带额外字段给条件用）。
     */
    public static List<FieldError> validateSubmission(ApprovalTemplate t, Map<String, Object> values) {
        List<FieldError> e = new ArrayList<>();
        for (ApprovalTemplate.FormField f : t.fields()) {
            Object v = values.get(f.code());
            String p = "fields." + f.code();
            boolean empty = v == null || (v instanceof String str && str.isBlank());
            if (empty) {
                if (f.required()) {
                    e.add(new FieldError(p, "opskit.validation.required"));
                }
                continue;
            }
            switch (f.type()) {
                case "TEXT", "TEXTAREA" -> {
                    if (!(v instanceof String str)) {
                        e.add(new FieldError(p, "opskit.validation.invalid"));
                    } else if (str.length() > ("TEXT".equals(f.type()) ? 200 : 4000)) {
                        e.add(new FieldError(p, "opskit.validation.too_long"));
                    }
                }
                case "NUMBER" -> {
                    if (!(v instanceof Number)) {
                        e.add(new FieldError(p, "opskit.validation.invalid"));
                    }
                }
                case "DATE" -> {
                    try {
                        java.time.LocalDate.parse(String.valueOf(v));
                    } catch (java.time.format.DateTimeParseException ex) {
                        e.add(new FieldError(p, "opskit.validation.invalid"));
                    }
                }
                case "SELECT" -> {
                    if (f.options().stream().noneMatch(o -> o.code().equals(String.valueOf(v)))) {
                        e.add(new FieldError(p, "opskit.validation.one_of"));
                    }
                }
                default -> { }
            }
        }
        return e;
    }

    /** 须先通过 {@link #validate}。 */
    public static ProcessDef compile(ApprovalTemplate t) {
        List<ApprovalStepDef> steps = t.steps().stream().map(s -> new ApprovalStepDef(s.code(), s.name(), when(s.when()),
                new ApproverRef(s.approver().kind(), s.approver().values()), ApprovalMode.valueOf(s.mode()),
                s.dueHours() == null ? null : Duration.ofHours(s.dueHours()))).toList();
        GateDef.Approval gate = new GateDef.Approval(steps, new ApprovalRules(t.rules().selfApproval(), t.rules().crossStepExclusive()),
                APPROVED, REJECTED, "withdrawn");
        Who requester = new Who(Set.of("REQUESTER"), null, null, null);
        return new ProcessDef(t.code(), t.name(), DRAFT,
                List.of(new StatusDef(DRAFT, text("草稿", "Draft", "مسودة"), StatusCategory.NEW, null),
                        new StatusDef(REVIEWING, text("审批中", "In review", "قيد المراجعة"), StatusCategory.IN_PROGRESS, gate),
                        new StatusDef(APPROVED, text("已通过", "Approved", "معتمد"), StatusCategory.DONE, null),
                        new StatusDef(REJECTED, text("已驳回", "Rejected", "مرفوض"), StatusCategory.CANCELLED, null)),
                List.of(new TransitionDef("submit", text("提交", "Submit", "إرسال"), Set.of(DRAFT), REVIEWING, requester, null, null, null, false),
                        new TransitionDef(APPROVED, text("通过", "Approve", "اعتماد"), Set.of(REVIEWING), APPROVED, null, null, null, null, true),
                        new TransitionDef(REJECTED, text("驳回", "Reject", "رفض"), Set.of(REVIEWING), REJECTED, null, null, null, null, true),
                        new TransitionDef("withdrawn", text("撤回", "Withdraw", "سحب"), Set.of(REVIEWING), DRAFT, null, null, null, null, true)),
                Set.of());
    }

    /** 控制台列表里的「运营审批 → 财务会签」。 */
    public static String stepsSummary(ApprovalTemplate t, String locale) {
        return String.join(" → ", t.steps().stream().map(s -> s.name() == null ? s.code() : s.name().resolve(locale, "en")).toList());
    }

    private static Condition when(ApprovalTemplate.When w) {
        if (w == null) {
            return null;
        }
        return switch (w.op()) {
            case "TRUE" -> new Condition.Compare(w.field(), Op.EQ, true);
            case "EQ" -> new Condition.Compare(w.field(), Op.EQ, w.value());
            case "IN" -> new Condition.Compare(w.field(), Op.IN, Arrays.stream(w.value().split(",")).map(String::strip).filter(v -> !v.isEmpty()).toList());
            default -> throw new IllegalArgumentException("unvalidated op " + w.op());
        };
    }

    private static boolean blank(LocalizedText t) {
        return t == null || t.values().values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private static LocalizedText text(String zh, String en, String ar) {
        return new LocalizedText(Map.of("zh", zh, "en", en, "ar", ar));
    }
}
