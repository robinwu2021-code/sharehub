package ai.neargo.opskit.process.api.model.query;

/** 队列组的待领数与我已领数；待领数 = tasks/query {queueGroup, status:[OPEN]} 的 total。 */
public record QueueCount(String queueGroup, long unclaimed, long claimedByMe) {
}
