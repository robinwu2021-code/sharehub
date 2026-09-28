package ai.neargo.opskit.process.api.model.query;

import java.util.List;

/**
 * 对账（21/01 INT-03）：一个业务对象上的全部实例，各带审批与完整流转。
 *
 * @param truncated 实例超过上限（{@link #LIMIT}）时为 true，此时 {@code instances} 只含最新的那些 —— 明说，不静默截断
 */
public record SubjectHistory(String type, String ref, long total, boolean truncated, List<Item> instances) {

    public static final int LIMIT = 200;

    public record Item(InstanceRow instance, List<ApprovalRow> approvals, List<HistoryEntry> history) {
    }
}
