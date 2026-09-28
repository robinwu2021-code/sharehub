package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.process.api.model.SubjectRef;

/**
 * 任务行（队列页 / 我已领取）。
 *
 * @param claimedBy        {@code member:x}；未领取时为空
 * @param claimExpiresAtMs 领取超时自动放回的时刻；队列组未配超时则为空
 */
public record QueueTask(String taskNo, String kind, String status, String queueGroup, String title, SubjectRef subject, Integer priorityOrd,
        long createdAtMs, String instanceNo, String approvalNo, String claimedBy, Long claimExpiresAtMs) {
}
