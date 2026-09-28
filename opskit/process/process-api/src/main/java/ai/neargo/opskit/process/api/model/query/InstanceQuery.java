package ai.neargo.opskit.process.api.model.query;

import java.util.List;

/** instances/query（21/06 §四）；各项为空 = 不限。 */
public record InstanceQuery(String processCode, List<String> statusCategory, List<String> status, Boolean open, String subjectType, String subjectRef,
        Long createdFromMs, Long createdToMs, Integer page, Integer size) {
}
