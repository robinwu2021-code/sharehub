package ai.neargo.opskit.ticket.api.model;

import java.util.List;

/**
 * 逐单结果：单张失败不回滚其余。
 *
 * @param remaining 按原受理人改派时，超出一次上限（200）还没处理的单数；0 = 全部处理完
 */
public record BulkReassignResult(List<String> succeeded, List<Failure> failed, int remaining) {

    public record Failure(String ticketNo, String error) {
    }
}
