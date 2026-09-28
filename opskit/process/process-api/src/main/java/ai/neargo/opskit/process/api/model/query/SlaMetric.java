package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;

/** 一个 SLA 指标的当前情况（TicketView.slaMetrics 的来源）。@param state PENDING / RUNNING / WARNING / PAUSED / BREACHED / MET / CANCELLED */
public record SlaMetric(String clockId, String policyCode, String metricCode, LocalizedText name, String state, Long startedAtMs, Long dueAtMs,
        long pausedMs, Long breachedAtMs, Long stoppedAtMs) {
}
