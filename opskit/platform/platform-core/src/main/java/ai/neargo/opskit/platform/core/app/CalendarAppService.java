package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.Permission;
import ai.neargo.opskit.platform.api.calendar.BusinessCalendar;
import ai.neargo.opskit.platform.api.calendar.CalendarDef;
import ai.neargo.opskit.platform.api.calendar.WorkingSlot;
import ai.neargo.opskit.platform.store.dao.CalendarDao;
import ai.neargo.opskit.platform.store.row.CalendarRow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionOperations;

/** 工作日历（21/07 O1-8）。保存前用 {@link BusinessCalendar} 校验一次：配错的日历存不进去。 */
public class CalendarAppService {

    private final CalendarDao calendars;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public CalendarAppService(CalendarDao calendars, Clock clock, TransactionOperations tx, AuditTrail audit) {
        this.calendars = calendars;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
    }

    /** {@code workspace} 为 {@link WorkspaceId#TEMPLATE} 时存为账户模板。 */
    @RequiresPermission("calendar:manage")
    public void upsert(Actor actor, WorkspaceId workspace, CalendarDef def) {
        Guards.require(actor, Permission.CALENDAR_MANAGE);
        if (!workspace.isTemplate() && !workspace.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        try {
            new BusinessCalendar(def);
        } catch (IllegalArgumentException e) {
            throw new OpsKitException(CommonErrors.VALIDATION, e.getMessage(), Map.of(),
                    List.of(new FieldError("calendar", "opskit.platform.invalid_calendar")));
        }
        List<Map<String, Object>> weekly = def.weekly().stream()
                .map(s -> Map.<String, Object>of("day", s.day().name(), "from", s.fromMinute(), "to", s.toMinute())).toList();
        CalendarRow row = new CalendarRow(actor.tenant().accountId().value(), workspace.value(), def.code(), def.zone(), weekly,
                def.holidays().stream().map(LocalDate::toString).collect(Collectors.toCollection(TreeSet::new)),
                actor.member().map(Object::toString).orElseGet(() -> "app:" + actor.app().orElse("?")), clock.millis());
        tx.executeWithoutResult(s -> {
            calendars.upsert(actor.tenant().accountId(), workspace, row);
            audit.record(actor.tenant().accountId(), workspace.value(), AuditTrail.who(actor), "calendar.upserted",
                    "calendar:" + def.code(), Map.of("zone", def.zone(), "slots", def.weekly().size(), "holidays", def.holidays().size()));
        });
    }

    @SystemAction("SLA 推算内部调用")
    public CalendarDef get(TenantContext t, String code) {
        CalendarRow r = calendars.resolve(t.accountId(), t.workspaceId(), code)
                .orElseThrow(() -> new OpsKitException(CommonErrors.NOT_FOUND, "calendar not found: " + code));
        List<WorkingSlot> slots = r.weekly().stream().map(m -> new WorkingSlot(DayOfWeek.valueOf((String) m.get("day")),
                ((Number) m.get("from")).intValue(), ((Number) m.get("to")).intValue())).toList();
        return new CalendarDef(r.calendarCode(), r.zone(), slots,
                r.holidays().stream().map(LocalDate::parse).collect(Collectors.toSet()));
    }
}
