package ai.neargo.opskit.ticket.api.model;

import java.util.List;

/** 工单统计（21/06 §五 ticket-stats；21/01 TKT-09）。每个数字都附 queryParams —— 原样回填 tickets/query 得到的就是这些单。 */
public final class TicketStats {

    private TicketStats() {
    }

    /**
     * 汇总卡。
     *
     * @param key   open · inProgress · dueSoon · breached · unassigned · escalated · closedToday
     * @param tone  warning / danger / 空
     */
    public record Card(String key, long value, String tone, TicketQuery queryParams) {
    }

    /**
     * 按人 / 按组的一行。
     *
     * @param metRate           区间内已关闭单里按时（MET）的占比，0–1；没有可计的为空
     * @param avgResolutionMs   区间内已关闭单「建单 → 关闭」的平均时长；没有为空
     */
    public record Row(String key, long total, long open, long openBreached, long closed, Double metRate, Long avgResolutionMs, TicketQuery queryParams) {
    }

    public record Summary(List<Card> cards, long todayStartMs, String zone) {
    }
}
