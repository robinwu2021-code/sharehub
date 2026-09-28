package ai.neargo.opskit.svc.api;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.platform.api.calendar.CalendarDef;
import ai.neargo.opskit.platform.api.calendar.WorkingSlot;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.core.app.CalendarAppService;
import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 空间设置：角色 → 产品权限（directory:manage）· 工作日历（calendar:manage）（21/06 §三）。 */
@RestController
@RequestMapping({"/v1/workspaces/{ws}", "/api/opskit/v1/workspaces/{ws}"})
public class SettingsController {

    private final DirectoryAppService directory;
    private final CalendarAppService calendars;

    public SettingsController(DirectoryAppService directory, CalendarAppService calendars) {
        this.directory = directory;
        this.calendars = calendars;
    }

    /** @param permissions 产品权限码（见 21/01 权限目录）；不认识的码逐条 422 */
    public record RoleBody(Map<String, String> name, Set<String> permissions) {
    }

    /** 建 / 改角色。令牌 roles 声明里的同名代号即生效；启动引导的 roles 段每次启动会按配置覆盖同名角色。 */
    @PutMapping("/roles/{code}")
    public ResponseEntity<Void> upsertRole(Actor actor, @PathVariable("code") String code, @RequestBody RoleBody body) {
        directory.upsertRole(actor, actor.tenant().workspaceId(), new RoleUpsert(code, body == null ? null : body.name(),
                body == null || body.permissions() == null ? Set.of() : new LinkedHashSet<>(body.permissions())));
        return ResponseEntity.noContent().build();
    }

    /** @param from / to {@code HH:mm}；to 可为 {@code 24:00} */
    public record SlotBody(String day, String from, String to) {
    }

    /** @param holidays {@code yyyy-MM-dd} */
    public record CalendarBody(String zone, List<SlotBody> weekly, List<String> holidays) {
    }

    /** 建 / 改工作日历（SLA 目标可引用它按工作时间计时）。 */
    @PutMapping("/calendars/{code}")
    public ResponseEntity<Void> upsertCalendar(Actor actor, @PathVariable("code") String code, @RequestBody CalendarBody body) {
        calendars.upsert(actor, actor.tenant().workspaceId(), parse(code, body));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calendars/{code}")
    public CalendarBody calendar(Actor actor, @PathVariable("code") String code) {
        CalendarDef d = calendars.get(actor.tenant(), code);
        return new CalendarBody(d.zone(), d.weekly().stream().map(s -> new SlotBody(s.day().name(), hhmm(s.fromMinute()), hhmm(s.toMinute()))).toList(),
                d.holidays().stream().sorted().map(LocalDate::toString).toList());
    }

    static CalendarDef parse(String code, CalendarBody b) {
        List<FieldError> errors = new ArrayList<>();
        if (b == null || b.zone() == null || b.zone().isBlank()) {
            errors.add(new FieldError("zone", "opskit.validation.required"));
        }
        List<WorkingSlot> slots = new ArrayList<>();
        List<SlotBody> weekly = b == null || b.weekly() == null ? List.of() : b.weekly();
        for (int i = 0; i < weekly.size(); i++) {
            SlotBody s = weekly.get(i);
            String p = "weekly[" + i + "]";
            try {
                DayOfWeek day = DayOfWeek.valueOf(String.valueOf(s.day()));
                slots.add(new WorkingSlot(day, minutes(s.from()), minutes(s.to())));
            } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
                errors.add(new FieldError(p, "opskit.validation.invalid"));
            }
        }
        Set<LocalDate> holidays = new LinkedHashSet<>();
        List<String> hs = b == null || b.holidays() == null ? List.of() : b.holidays();
        for (int i = 0; i < hs.size(); i++) {
            try {
                holidays.add(LocalDate.parse(hs.get(i)));
            } catch (RuntimeException e) {
                errors.add(new FieldError("holidays[" + i + "]", "opskit.validation.invalid"));
            }
        }
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid calendar", Map.of("code", code), errors);
        }
        return new CalendarDef(code, b.zone(), slots, holidays);
    }

    private static int minutes(String hhmm) {
        if ("24:00".equals(hhmm)) {
            return WorkingSlot.END_OF_DAY;
        }
        LocalTime t = LocalTime.parse(hhmm);
        return t.getHour() * 60 + t.getMinute();
    }

    private static String hhmm(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
