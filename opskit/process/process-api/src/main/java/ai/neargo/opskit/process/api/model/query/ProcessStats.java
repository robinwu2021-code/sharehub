package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.Map;

/**
 * 流程统计（按流程，创建时间落在区间内的实例）。
 *
 * @param byCategory 状态类别 → 数量（五种都给，没有为 0）
 * @param avgCycleMs 已关闭实例「创建 → 关闭」的平均时长；没有已关闭的为空
 * @param approvals  审批单状态 → 数量（PENDING / APPROVED / REJECTED / WITHDRAWN，没有为 0）
 */
public record ProcessStats(String processCode, LocalizedText processName, long total, long open, Map<String, Long> byCategory, Long avgCycleMs,
        Map<String, Long> approvals) {
}
