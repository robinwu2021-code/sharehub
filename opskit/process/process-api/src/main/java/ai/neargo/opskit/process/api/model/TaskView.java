package ai.neargo.opskit.process.api.model;

import java.util.Set;

public record TaskView(String taskNo, String kind, String instanceNo, String approvalNo, String stepCode, String queueGroup, String title,
        Integer priorityOrd, String status, String assignee, Set<String> candidates, Long claimedAtMs, Long claimExpiresAtMs, Long dueAtMs,
        long createdAtMs) {
}
