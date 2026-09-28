package ai.neargo.opskit.process.api.model.query;

/** 待办四个页签的角标（todos/count），与 todos/query 同口径。 */
public record TodoCount(long all, long process, long ticket, long claimable) {
}
