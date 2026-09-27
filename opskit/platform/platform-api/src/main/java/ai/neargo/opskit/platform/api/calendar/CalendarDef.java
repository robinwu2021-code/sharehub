package ai.neargo.opskit.platform.api.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 工作日历（21/02 §6.5 · 21/04 平台的日历表）。
 *
 * @param zone     IANA 时区；营业时段与节假日都按它解释（存储一律 UTC）
 * @param holidays 该时区下的日期
 */
public record CalendarDef(String code, String zone, List<WorkingSlot> weekly, Set<LocalDate> holidays) {
    public CalendarDef {
        weekly = weekly == null ? List.of() : List.copyOf(weekly);
        holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
    }
}
