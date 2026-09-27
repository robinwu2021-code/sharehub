package ai.neargo.opskit.process.api.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param participants 参与者角色 → 主体（{@code member:…} / {@code group:…} / {@code contact:…}）；
 *                     未给 REQUESTER 时取发起的成员
 */
public record StartRequest(String processCode, SubjectRef subject, Map<String, Object> fields, Map<String, List<String>> participants,
        Set<String> scopes) {
    public StartRequest {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        participants = participants == null ? Map.of() : Map.copyOf(participants);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
