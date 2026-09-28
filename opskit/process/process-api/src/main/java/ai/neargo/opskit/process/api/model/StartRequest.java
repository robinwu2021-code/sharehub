package ai.neargo.opskit.process.api.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param slaPolicies  引用的 SLA 策略代号（21/02 §6.5；工单模块传工单类型绑定的策略）
 * @param participants 参与者角色 → 主体（{@code member:…} / {@code group:…} / {@code contact:…}）；
 *                     未给 REQUESTER 时取发起的成员
 */
public record StartRequest(String processCode, SubjectRef subject, Map<String, Object> fields, Map<String, List<String>> participants,
        Set<String> scopes, List<String> slaPolicies) {
    public StartRequest {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        participants = participants == null ? Map.of() : Map.copyOf(participants);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        slaPolicies = slaPolicies == null ? List.of() : List.copyOf(slaPolicies);
    }

    /** 不引用 SLA 策略。 */
    public StartRequest(String processCode, SubjectRef subject, Map<String, Object> fields, Map<String, List<String>> participants, Set<String> scopes) {
        this(processCode, subject, fields, participants, scopes, List.of());
    }
}
