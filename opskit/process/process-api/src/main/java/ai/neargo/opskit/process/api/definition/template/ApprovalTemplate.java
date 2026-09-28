package ai.neargo.opskit.process.api.definition.template;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import java.util.List;

/**
 * 审批模板（21/02 §6.4a）：控制台编辑的线性审批步骤，由服务端编译成标准的审批类流程。
 * 这里只做形状，不在构造时校验 —— 校验要给出按模板字段的逐条错误（{@code steps[1].approver.value}），见 ApprovalTemplateValidator。
 *
 * @param name   至少一种语言非空；为空时是 null（由校验报 {@code name}）
 * @param fields 发起表单（可空）：运营台「发起审批」按它渲染与校验；发起时填的值成为实例字段，步骤条件与 FIELD 审批人可引用
 */
public record ApprovalTemplate(String code, LocalizedText name, List<Step> steps, Rules rules, List<FormField> fields) {

    public ApprovalTemplate {
        steps = steps == null ? List.of() : List.copyOf(steps);
        rules = rules == null ? Rules.DEFAULT : rules;
        fields = fields == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(fields));
    }

    /** 没有发起表单（由宿主系统送审的模板）。 */
    public ApprovalTemplate(String code, LocalizedText name, List<Step> steps, Rules rules) {
        this(code, name, steps, rules, List.of());
    }

    /**
     * 发起表单的一个字段。
     *
     * @param type    TEXT / TEXTAREA / NUMBER / DATE（yyyy-MM-dd）/ SELECT；原样保留字符串，非法值由校验报 {@code fields[i].type}
     * @param options 仅 SELECT：选项代号与显示名
     */
    public record FormField(String code, LocalizedText name, String type, boolean required, List<Option> options) {
        public FormField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record Option(String code, LocalizedText name) {
    }

    /**
     * @param mode     ANY（或签）/ ALL（会签）；原样保留字符串，非法值由校验报 {@code steps[i].mode}
     * @param dueHours 步骤时限（小时），可空
     */
    public record Step(String code, LocalizedText name, Approver approver, String mode, When when, Integer dueHours) {
    }

    /**
     * @param values MEMBER 为成员 ID 列表；GROUP / ROLE / PERM 为一个代号；FIELD 为一个字段代号
     */
    public record Approver(ApproverKind kind, List<String> values) {
        public Approver {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * 进入条件：只支持「字段为真 / 等于 / 属于」（op = TRUE / EQ / IN）；IN 的 value 为逗号分隔。
     */
    public record When(String field, String op, String value) {
    }

    /** 驳回必填原因恒开，不在这里。 */
    public record Rules(boolean selfApproval, boolean crossStepExclusive) {
        public static final Rules DEFAULT = new Rules(false, true);
    }
}
