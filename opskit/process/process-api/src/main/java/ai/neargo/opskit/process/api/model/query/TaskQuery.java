package ai.neargo.opskit.process.api.model.query;

import java.util.List;

/**
 * tasks/query（21/06 §四，2026-09-28 与前端对齐的参数名）。
 *
 * @param status   任务状态 OPEN / CLAIMED / ASSIGNED / DONE / CANCELLED；空 = 未结束的三种
 * @param claimed  true = 已被领取；false = 未被领取
 * @param assignee {@code "me"} 或成员 ID
 * @param kind     APPROVAL（审核队列）/ WORK（工单办理）；空 = 两种
 */
public record TaskQuery(String queueGroup, List<String> status, Boolean claimed, String assignee, String kind, Integer page, Integer size) {

    /** 不按任务种类筛。 */
    public TaskQuery(String queueGroup, List<String> status, Boolean claimed, String assignee, Integer page, Integer size) {
        this(queueGroup, status, claimed, assignee, null, page, size);
    }
}
