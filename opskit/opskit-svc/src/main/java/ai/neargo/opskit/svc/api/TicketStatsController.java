package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.ticket.api.model.TicketQuery;
import ai.neargo.opskit.ticket.api.model.TicketStats;
import ai.neargo.opskit.ticket.core.app.TicketStatsService;
import ai.neargo.opskit.svc.api.Present.People;
import ai.neargo.opskit.svc.api.Present.Person;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工单统计（21/06 §五）：每个数字附 queryParams，原样回填 tickets/query 就是那些单。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class TicketStatsController {

    private final TicketStatsService stats;
    private final DirectoryAppService directory;

    public TicketStatsController(TicketStatsService stats, DirectoryAppService directory) {
        this.stats = stats;
        this.directory = directory;
    }

    /** 与前端 TicketStat 同形：{key, label, value, tone?, queryParams}。 */
    public record CardDto(String key, String label, long value, String tone, TicketQuery queryParams) {
    }

    public record RowDto(String key, Person assignee, String groupName, long total, long open, long openBreached, long closed, Double metRate,
            Long avgResolutionMs, TicketQuery queryParams) {
    }

    public record GroupBody(List<String> groups, TicketQuery filter) {
    }

    public record CountDto(long open) {
    }

    private static final Map<String, Map<String, String>> LABELS = Map.of(
            "open", Map.of("zh", "未结", "en", "Open", "ar", "مفتوحة"),
            "inProgress", Map.of("zh", "处理中", "en", "In progress", "ar", "قيد المعالجة"),
            "dueSoon", Map.of("zh", "即将超时", "en", "Due soon", "ar", "قاربت على التجاوز"),
            "breached", Map.of("zh", "已超时", "en", "Breached", "ar", "متجاوزة"),
            "unassigned", Map.of("zh", "待派", "en", "Unassigned", "ar", "غير مسندة"),
            "escalated", Map.of("zh", "已升级", "en", "Escalated", "ar", "مُصعّدة"),
            "closedToday", Map.of("zh", "今日关闭", "en", "Closed today", "ar", "أُغلقت اليوم"));

    /** 请求体是公共筛选（可空）：{typeCode?, scopes?, assignee?, …}，并入每张卡。 */
    @PostMapping("/ticket-stats/summary")
    public List<CardDto> summary(Actor actor, @RequestBody(required = false) TicketQuery base, HttpServletRequest req) {
        String locale = Present.locale(req);
        return stats.summary(actor, base).cards().stream().map(c -> new CardDto(c.key(),
                LABELS.getOrDefault(c.key(), Map.of()).getOrDefault(locale, c.key()), c.value(), c.tone(), c.queryParams())).toList();
    }

    @PostMapping("/ticket-stats/open-count")
    public CountDto openCount(Actor actor, @RequestBody(required = false) TicketQuery base) {
        return new CountDto(stats.openCount(actor, base));
    }

    @PostMapping("/ticket-stats/by-assignee")
    public List<RowDto> byAssignee(Actor actor, @RequestBody(required = false) TicketQuery base) {
        List<TicketStats.Row> rows = stats.byAssignee(actor, base);
        People people = new People(directory, actor.tenant(), rows.stream().map(r -> r.key() == null ? null : "member:" + r.key()).toList());
        return rows.stream().map(r -> new RowDto(r.key(), r.key() == null ? null : people.of("member:" + r.key()), null, r.total(), r.open(), r.openBreached(),
                r.closed(), r.metRate(), r.avgResolutionMs(), r.queryParams())).toList();
    }

    @PostMapping("/ticket-stats/by-group")
    public List<RowDto> byGroup(Actor actor, @RequestBody GroupBody b, HttpServletRequest req) {
        String locale = Present.locale(req);
        return stats.byGroup(actor, b.filter(), b.groups()).stream().map(r -> new RowDto(r.key(), null,
                directory.findGroup(actor.tenant(), r.key()).filter(g -> !g.name().isEmpty())
                        .map(g -> new ai.neargo.opskit.commons.i18n.LocalizedText(g.name()).resolve(locale, "en")).orElse(r.key()),
                r.total(), r.open(), r.openBreached(), r.closed(), r.metRate(), r.avgResolutionMs(), r.queryParams())).toList();
    }
}
