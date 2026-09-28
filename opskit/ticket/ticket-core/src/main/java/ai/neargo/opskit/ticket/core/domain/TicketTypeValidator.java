package ai.neargo.opskit.ticket.core.domain;

import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef;
import ai.neargo.opskit.ticket.api.definition.AssignmentDef.Strategy;
import ai.neargo.opskit.ticket.api.definition.FieldDef;
import ai.neargo.opskit.ticket.api.definition.FieldType;
import ai.neargo.opskit.ticket.api.definition.OptionDef;
import ai.neargo.opskit.ticket.api.definition.OptionSetDef;
import ai.neargo.opskit.ticket.api.definition.Options;
import ai.neargo.opskit.ticket.api.definition.TicketTypeDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工单类型 / 选项集的结构校验（21/07 O3-1）：流程存在 · 字段与引用一致 · 派单策略自洽。错误路径按定义字段给（{@code fields[2].options}）。
 * 纯函数：外部引用经 {@link Lookup} 查。
 */
public final class TicketTypeValidator {

    /** 校验要查的外部事实。 */
    public interface Lookup {
        Optional<ProcessDef> process(String code);

        Optional<OptionSetDef> optionSet(String code);

        boolean slaPolicy(String code);

        boolean group(String code);
    }

    /** 工单自带的列，字段代号不能与之同名（查询参数、列表列会撞）。 */
    public static final Set<String> RESERVED = Set.of("title", "description", "priority", "resolution", "status", "assignee", "requester",
            "ticketNo", "type", "createdAt", "updatedAt");

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[a-z][A-Za-z0-9_]{0,63}");
    private static final Pattern OPTION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_\\-]{0,31}");
    private static final Pattern PREFIX = Pattern.compile("[A-Z]{1,6}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z][A-Za-z0-9_\\-]{0,31}");
    private static final Set<FieldType> NOT_FILTERABLE = Set.of(FieldType.LONG_TEXT, FieldType.ATTACHMENT);
    private static final Set<FieldType> SCOPE_SOURCES = Set.of(FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.MEMBER,
            FieldType.GROUP, FieldType.REF);

    private TicketTypeValidator() {
    }

    public static List<FieldError> validate(TicketTypeDef t, Lookup lk) {
        List<FieldError> e = new ArrayList<>();
        if (t.code() == null || !CODE.matcher(t.code()).matches()) {
            e.add(new FieldError("code", "opskit.validation.code_format"));
        }
        if (blank(t.name())) {
            e.add(new FieldError("name", "opskit.validation.required"));
        }
        Optional<ProcessDef> process = t.processCode() == null ? Optional.empty() : lk.process(t.processCode());
        if (process.isEmpty()) {
            e.add(new FieldError("processCode", "opskit.ticket.unknown_process"));
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < t.fields().size(); i++) {
            fieldErrors(t.fields().get(i), "fields[" + i + "]", codes, lk, e);
        }
        optionErrors(t.priorityOptions(), "priorityOptions", lk, e);
        optionErrors(t.resolutionOptions(), "resolutionOptions", lk, e);
        if (t.numberPrefix() != null && !PREFIX.matcher(t.numberPrefix()).matches()) {
            e.add(new FieldError("numberPrefix", "opskit.ticket.prefix_format"));
        }
        assignmentErrors(t, lk, e);
        for (int i = 0; i < t.slaPolicies().size(); i++) {
            if (!lk.slaPolicy(t.slaPolicies().get(i))) {
                e.add(new FieldError("slaPolicies[" + i + "]", "opskit.sla.unknown_policy"));
            }
        }
        for (int i = 0; i < t.scopeFrom().size(); i++) {
            TicketTypeDef.ScopeFrom s = t.scopeFrom().get(i);
            Optional<FieldDef> f = s.field() == null ? Optional.empty() : t.field(s.field());
            if (f.isEmpty()) {
                e.add(new FieldError("scopeFrom[" + i + "].field", "opskit.ticket.unknown_field"));
            } else if (!SCOPE_SOURCES.contains(f.get().type())) {
                e.add(new FieldError("scopeFrom[" + i + "].field", "opskit.ticket.scope_source_type"));
            }
            if (s.dimension() == null || !DIMENSION.matcher(s.dimension()).matches()) {
                e.add(new FieldError("scopeFrom[" + i + "].dimension", "opskit.validation.code_format"));
            }
        }
        if (t.dedupe() != null) {
            if (t.dedupe().fields().isEmpty()) {
                e.add(new FieldError("dedupe.fields", "opskit.validation.required"));
            }
            for (int i = 0; i < t.dedupe().fields().size(); i++) {
                Optional<FieldDef> f = t.field(t.dedupe().fields().get(i));
                if (f.isEmpty()) {
                    e.add(new FieldError("dedupe.fields[" + i + "]", "opskit.ticket.unknown_field"));
                } else if (NOT_FILTERABLE.contains(f.get().type())) {
                    e.add(new FieldError("dedupe.fields[" + i + "]", "opskit.ticket.dedupe_field_type"));
                }
            }
        }
        if (t.mergeTransition() != null && process.isPresent()
                && process.get().transitions().stream().noneMatch(tr -> tr.code().equals(t.mergeTransition()))) {
            e.add(new FieldError("mergeTransition", "opskit.ticket.unknown_transition"));
        }
        return e;
    }

    public static List<FieldError> validate(OptionSetDef s) {
        List<FieldError> e = new ArrayList<>();
        if (s.code() == null || !CODE.matcher(s.code()).matches()) {
            e.add(new FieldError("code", "opskit.validation.code_format"));
        }
        if (blank(s.name())) {
            e.add(new FieldError("name", "opskit.validation.required"));
        }
        inlineErrors(s.options(), "options", e);
        return e;
    }

    private static void fieldErrors(FieldDef f, String p, Set<String> codes, Lookup lk, List<FieldError> e) {
        if (f.code() == null || !FIELD.matcher(f.code()).matches()) {
            e.add(new FieldError(p + ".code", "opskit.validation.code_format"));
        } else if (RESERVED.contains(f.code())) {
            e.add(new FieldError(p + ".code", "opskit.ticket.reserved_field"));
        } else if (!codes.add(f.code())) {
            e.add(new FieldError(p + ".code", "opskit.validation.duplicate"));
        }
        if (blank(f.name())) {
            e.add(new FieldError(p + ".name", "opskit.validation.required"));
        }
        if (f.type() == null) {
            e.add(new FieldError(p + ".type", "opskit.validation.required"));
            return;
        }
        if (f.type().hasOptions()) {
            if (f.options() == null) {
                e.add(new FieldError(p + ".options", "opskit.validation.required"));
            } else {
                optionErrors(f.options(), p + ".options", lk, e);
            }
        } else if (f.options() != null) {
            e.add(new FieldError(p + ".options", "opskit.ticket.options_not_applicable"));
        }
        if (f.multiple() && f.type() != FieldType.MEMBER) {
            e.add(new FieldError(p + ".multiple", "opskit.ticket.multiple_not_applicable"));
        }
        if (f.refType() != null && f.type() != FieldType.REF) {
            e.add(new FieldError(p + ".refType", "opskit.ticket.ref_type_not_applicable"));
        }
        if (f.required() && !f.onCreate()) {
            // 必填却不许建单时填 = 这张单永远建不出来
            e.add(new FieldError(p + ".onCreate", "opskit.ticket.required_needs_on_create"));
        }
        if (f.filterable() && NOT_FILTERABLE.contains(f.type())) {
            e.add(new FieldError(p + ".filterable", "opskit.ticket.not_filterable_type"));
        }
    }

    private static void optionErrors(Options o, String p, Lookup lk, List<FieldError> e) {
        if (o == null) {
            return;
        }
        if (o.optionSet() != null) {
            if (!o.inline().isEmpty()) {
                e.add(new FieldError(p, "opskit.ticket.options_either_inline_or_set"));
            } else if (lk.optionSet(o.optionSet()).isEmpty()) {
                e.add(new FieldError(p + ".optionSet", "opskit.ticket.unknown_option_set"));
            }
            return;
        }
        inlineErrors(o.inline(), p + ".inline", e);
    }

    private static void inlineErrors(List<OptionDef> options, String p, List<FieldError> e) {
        if (options.isEmpty()) {
            e.add(new FieldError(p, "opskit.validation.required"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            OptionDef o = options.get(i);
            if (o.code() == null || !OPTION.matcher(o.code()).matches()) {
                e.add(new FieldError(p + "[" + i + "].code", "opskit.validation.code_format"));
            } else if (!seen.add(o.code())) {
                e.add(new FieldError(p + "[" + i + "].code", "opskit.validation.duplicate"));
            }
            if (blank(o.name())) {
                e.add(new FieldError(p + "[" + i + "].name", "opskit.validation.required"));
            }
        }
    }

    private static void assignmentErrors(TicketTypeDef t, Lookup lk, List<FieldError> e) {
        AssignmentDef a = t.assignment();
        if (a.strategy() == null) {
            e.add(new FieldError("assignment.strategy", "opskit.validation.required"));
            return;
        }
        strategyNeeds(a.strategy(), a, t, lk, e);
        if (a.strategy() == Strategy.EXTERNAL) {
            if (a.externalTimeoutSec() == null || a.externalTimeoutSec() < 1) {
                e.add(new FieldError("assignment.externalTimeoutSec", "opskit.validation.required"));
            }
            if (a.fallback() == null || a.fallback() == Strategy.EXTERNAL) {
                e.add(new FieldError("assignment.fallback", "opskit.ticket.fallback_invalid"));
            } else {
                strategyNeeds(a.fallback(), a, t, lk, e);
            }
        }
    }

    private static void strategyNeeds(Strategy s, AssignmentDef a, TicketTypeDef t, Lookup lk, List<FieldError> e) {
        switch (s) {
            case QUEUE, ROUND_ROBIN, LEAST_LOADED -> {
                if (a.group() == null || !lk.group(a.group())) {
                    e.add(new FieldError("assignment.group", "opskit.ticket.unknown_group"));
                }
            }
            case FIELD -> {
                Optional<FieldDef> f = a.field() == null ? Optional.empty() : t.field(a.field());
                if (f.isEmpty() || f.get().type() != FieldType.MEMBER || f.get().multiple()) {
                    e.add(new FieldError("assignment.field", "opskit.ticket.assign_field_must_be_single_member"));
                }
            }
            case MANUAL, EXTERNAL -> {
            }
        }
    }

    private static boolean blank(LocalizedText t) {
        return t == null || t.values().values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
