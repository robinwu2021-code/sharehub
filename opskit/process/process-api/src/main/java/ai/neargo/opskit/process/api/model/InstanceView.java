package ai.neargo.opskit.process.api.model;

import ai.neargo.opskit.process.api.definition.StatusCategory;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record InstanceView(String instanceNo, String processCode, String processVersion, SubjectRef subject, String status,
        StatusCategory statusCategory, boolean open, Map<String, Object> fields, Map<String, Set<String>> participants,
        Set<String> scopes, int seq, List<ActionView> actions, long createdAtMs, long updatedAtMs) {
}
