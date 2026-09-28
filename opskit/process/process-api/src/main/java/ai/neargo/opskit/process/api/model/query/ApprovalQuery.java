package ai.neargo.opskit.process.api.model.query;

import java.util.List;

/** approvals/query；approvals/count 用同样的参数（status 除外）。 */
public record ApprovalQuery(List<String> status, String processCode, String requestedBy, Long fromMs, String keyword, Integer page, Integer size) {
}
