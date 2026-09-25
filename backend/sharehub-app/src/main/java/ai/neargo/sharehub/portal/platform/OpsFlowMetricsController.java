package ai.neargo.sharehub.portal.platform;

import ai.neargo.sharehub.operation.service.OpsFlowMetricsService;
import ai.neargo.sharehub.operation.service.OpsFlowMetricsService.OpsFlowMetrics;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 运营核心流程指标（G5）。窗口缺省 = 最近 30 天。 */
@RestController
public class OpsFlowMetricsController {

    private final OpsFlowMetricsService metrics;

    public OpsFlowMetricsController(OpsFlowMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/ops/ops-flow-metrics")
    @PreAuthorize("@perm.can('dashboard:overview:read')")
    public OpsFlowMetrics metrics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate t = to == null ? LocalDate.now().plusDays(1) : to;
        LocalDate f = from == null ? t.minusDays(30) : from;
        return metrics.metrics(f, t);
    }
}
