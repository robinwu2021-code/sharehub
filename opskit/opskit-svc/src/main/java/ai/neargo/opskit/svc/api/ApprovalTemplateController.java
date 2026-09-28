package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.template.ApprovalTemplate;
import ai.neargo.opskit.process.core.app.ApprovalTemplateAppService;
import ai.neargo.opskit.process.core.domain.definition.ApprovalTemplateCompiler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批模板（21/06 §四 · 21/02 §6.4a）。线上形状与控制台一致：{@code approver.value} 在 MEMBER 时是成员 ID 列表，其余是一个代号。
 * 422 的 {@code errors[].path} 按模板字段给（{@code steps[1].approver.value}）。
 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class ApprovalTemplateController {

    private final ApprovalTemplateAppService templates;

    public ApprovalTemplateController(ApprovalTemplateAppService templates) {
        this.templates = templates;
    }

    public record ApproverDto(String kind, Object value) {
    }

    public record WhenDto(String field, String op, String value) {
    }

    public record StepDto(String code, Map<String, String> name, ApproverDto approver, String mode, WhenDto when, Integer dueHours) {
    }

    public record RulesDto(Boolean selfApproval, Boolean crossStepExclusive) {
    }

    /** PUT 的请求体 = 模板 + 可选 note；GET 的响应不带 note。 */
    public record TemplateDto(String code, Map<String, String> name, List<StepDto> steps, RulesDto rules, String note) {
    }

    public record SummaryDto(String code, String name, Map<String, String> names, String stepsSummary, String version, long running, boolean enabled,
            boolean inherited) {
    }

    public record PublishDto(String version, boolean created, long runningOnOlderVersions) {
    }

    @GetMapping("/approval-templates")
    public List<SummaryDto> list(Actor actor, HttpServletRequest req) {
        String locale = Present.locale(req);
        return templates.list(actor.tenant()).stream().map(s -> new SummaryDto(s.template().code(), Present.text(s.template().name(), locale),
                s.template().name() == null ? Map.of() : s.template().name().values(), ApprovalTemplateCompiler.stepsSummary(s.template(), locale),
                s.version(), s.running(), s.enabled(), s.inherited())).toList();
    }

    @GetMapping("/approval-templates/{code}")
    public TemplateDto get(Actor actor, @PathVariable("code") String code) {
        return templates.get(actor.tenant(), code).map(ApprovalTemplateController::toDto)
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "approval template not found"));
    }

    @GetMapping("/approval-templates/{code}/versions")
    public List<ApprovalTemplateAppService.Version> versions(Actor actor, @PathVariable("code") String code) {
        return templates.versions(actor.tenant(), code);
    }

    /** 幂等发布：内容没变返回 created=false，不生成新版本。控制台（成员令牌）记为 CONSOLE，应用调用记为 API。 */
    @PutMapping("/approval-templates/{code}")
    public PublishDto publish(Actor actor, @PathVariable("code") String code, @RequestBody TemplateDto body) {
        if (body.code() != null && !body.code().equals(code)) {
            throw new OpsKitException(CommonErrors.VALIDATION, "code in body differs from path", Map.of(), List.of(new FieldError("code", "opskit.validation.mismatch")));
        }
        ApprovalTemplateAppService.Published p = templates.publish(actor, actor.tenant().workspaceId(), fromDto(code, body), body.note(),
                actor.isAppItself() ? "API" : "CONSOLE");
        return new PublishDto(p.version(), p.created(), p.runningOnOlderVersions());
    }

    @PostMapping("/approval-templates/{code}/disable")
    public ResponseEntity<Void> disable(Actor actor, @PathVariable("code") String code) {
        templates.setEnabled(actor, actor.tenant().workspaceId(), code, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/approval-templates/{code}/enable")
    public ResponseEntity<Void> enable(Actor actor, @PathVariable("code") String code) {
        templates.setEnabled(actor, actor.tenant().workspaceId(), code, true);
        return ResponseEntity.noContent().build();
    }

    // ── 线上形状 ⇄ 模板 ─────────────────────────────────────
    /** 形状层面的错（kind 不认识、value 类型不对）也按模板字段报，和业务校验一起返回。 */
    static ApprovalTemplate fromDto(String code, TemplateDto d) {
        List<FieldError> shape = new ArrayList<>();
        List<ApprovalTemplate.Step> steps = new ArrayList<>();
        List<StepDto> in = d.steps() == null ? List.of() : d.steps();
        for (int i = 0; i < in.size(); i++) {
            StepDto s = in.get(i);
            if (s == null) {
                steps.add(null);
                continue;
            }
            ApprovalTemplate.Approver approver = null;
            if (s.approver() != null && s.approver().kind() != null) {
                ApproverKind kind = null;
                try {
                    kind = ApproverKind.valueOf(s.approver().kind());
                } catch (IllegalArgumentException e) {
                    shape.add(new FieldError("steps[" + i + "].approver.kind", "opskit.validation.one_of"));
                }
                if (kind != null) {
                    approver = new ApprovalTemplate.Approver(kind, values(s.approver().value(), kind, i, shape));
                }
            }
            steps.add(new ApprovalTemplate.Step(s.code(), text(s.name()), approver, s.mode(),
                    s.when() == null ? null : new ApprovalTemplate.When(s.when().field(), s.when().op(), s.when().value()), s.dueHours()));
        }
        if (!shape.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid approval template", Map.of("code", code), shape);
        }
        ApprovalTemplate.Rules rules = d.rules() == null ? ApprovalTemplate.Rules.DEFAULT
                : new ApprovalTemplate.Rules(Boolean.TRUE.equals(d.rules().selfApproval()), !Boolean.FALSE.equals(d.rules().crossStepExclusive()));
        return new ApprovalTemplate(code, text(d.name()), steps, rules);
    }

    private static List<String> values(Object v, ApproverKind kind, int i, List<FieldError> shape) {
        if (v == null) {
            return List.of();
        }
        if (kind == ApproverKind.MEMBER) {
            if (v instanceof Collection<?> c) {
                return c.stream().map(x -> x == null ? null : String.valueOf(x)).toList();
            }
            shape.add(new FieldError("steps[" + i + "].approver.value", "opskit.template.member_list_expected"));
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        shape.add(new FieldError("steps[" + i + "].approver.value", "opskit.template.code_expected"));
        return List.of();
    }

    /** 去掉空白语种；全空 = 没给（由校验报 name）。 */
    private static LocalizedText text(Map<String, String> m) {
        if (m == null) {
            return null;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                kept.put(k, v.strip());
            }
        });
        return kept.isEmpty() ? null : new LocalizedText(kept);
    }

    static TemplateDto toDto(ApprovalTemplate t) {
        return new TemplateDto(t.code(), t.name() == null ? Map.of() : t.name().values(), t.steps().stream().map(s -> new StepDto(s.code(),
                s.name() == null ? Map.of() : s.name().values(),
                new ApproverDto(s.approver().kind().name(), s.approver().kind() == ApproverKind.MEMBER ? s.approver().values() : s.approver().values().get(0)),
                s.mode(), s.when() == null ? null : new WhenDto(s.when().field(), s.when().op(), s.when().value()), s.dueHours())).toList(),
                new RulesDto(t.rules().selfApproval(), t.rules().crossStepExclusive()), null);
    }
}
