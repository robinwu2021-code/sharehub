package ai.neargo.opskit.process.api.model.query;

import java.util.List;

/**
 * tasks/query（21/06 §四，2026-09-28 与前端对齐的参数名）。
 *
 * @param status   任务状态 OPEN / CLAIMED / ASSIGNED / DONE / CANCELLED；空 = 未结束的三种
 * @param claimed  true = 已被领取；false = 未被领取
 * @param assignee {@code "me"} 或成员 ID
 */
public record TaskQuery(String queueGroup, List<String> status, Boolean claimed, String assignee, Integer page, Integer size) {
}
