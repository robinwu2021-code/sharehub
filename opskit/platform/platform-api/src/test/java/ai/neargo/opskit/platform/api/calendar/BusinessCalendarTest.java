package ai.neargo.opskit.platform.api.calendar;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 21/07 O1-8 验收：迪拜 / 上海 / UTC 三时区边界 + 夏令时。 */
class BusinessCalendarTest {

    private static List<WorkingSlot> weekdays(String from, String to, DayOfWeek... days) {
        return Stream.of(days).map(d -> WorkingSlot.parse(d, from, to)).toList();
    }

    private static final DayOfWeek[] MON_FRI = {MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY};

    @Test
    void dubaiCarriesOverTheWeekend() {
        // 迪拜 UTC+4，周一至周五 09:00–18:00（2022 年起周末为周六日）
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("dxb", "Asia/Dubai", weekdays("09:00", "18:00", MON_FRI), Set.of()));
        Instant friday1700 = Instant.parse("2026-10-02T13:00:00Z");   // 周五 17:00 当地
        // 剩 1 小时到 18:00，余下 3 小时落到下周一 09:00 起 → 周一 12:00 当地 = 08:00Z
        assertThat(cal.plus(friday1700, Duration.ofHours(4))).isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
    }

    @Test
    void dubaiStartBeforeOpeningCountsFromOpening() {
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("dxb", "Asia/Dubai", weekdays("09:00", "18:00", MON_FRI), Set.of()));
        Instant monday0600 = Instant.parse("2026-10-05T02:00:00Z");
        assertThat(cal.plus(monday0600, Duration.ofMinutes(30))).isEqualTo(Instant.parse("2026-10-05T05:30:00Z")); // 09:30 当地
        assertThat(cal.isWorking(monday0600)).isFalse();
    }

    @Test
    void shanghaiSkipsNationalDayHolidays() {
        Set<LocalDate> nationalDay = Stream.iterate(LocalDate.of(2026, 10, 1), d -> d.plusDays(1)).limit(7).collect(java.util.stream.Collectors.toSet());
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("sha", "Asia/Shanghai", weekdays("09:00", "18:00", MON_FRI), nationalDay));
        Instant sep30_1700 = Instant.parse("2026-09-30T09:00:00Z"); // 周三 17:00 当地
        // 剩 1 小时，10/1–10/7 放假，10/8 周四 09:00 起再算 1 小时 → 10:00 当地 = 02:00Z
        assertThat(cal.plus(sep30_1700, Duration.ofHours(2))).isEqualTo(Instant.parse("2026-10-08T02:00:00Z"));
        assertThat(cal.between(sep30_1700, Instant.parse("2026-10-08T02:00:00Z"))).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void utcOvernightShiftIsContinuousAcrossMidnight() {
        List<WorkingSlot> night = List.of(WorkingSlot.parse(MONDAY, "22:00", "24:00"), WorkingSlot.parse(TUESDAY, "00:00", "06:00"));
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("night", "UTC", night, Set.of()));
        Instant mon2300 = Instant.parse("2026-10-05T23:00:00Z");
        assertThat(cal.plus(mon2300, Duration.ofHours(3))).isEqualTo(Instant.parse("2026-10-06T02:00:00Z"));
        // 恰好用完一个时段：到期落在时段结束处（周二 06:00），而不是跳到下周
        assertThat(cal.plus(mon2300, Duration.ofHours(7))).isEqualTo(Instant.parse("2026-10-06T06:00:00Z"));
        // 超出：下周一 22:00 起再算 1 小时
        assertThat(cal.plus(mon2300, Duration.ofHours(8))).isEqualTo(Instant.parse("2026-10-12T23:00:00Z"));
    }

    @Test
    void daylightSavingDayHasTheRealElapsedLength() {
        // 纽约 2026-03-08（周日）02:00 跳到 03:00，00:00–06:00 实际只有 5 小时
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("nyc", "America/New_York",
                List.of(WorkingSlot.parse(SUNDAY, "00:00", "06:00")), Set.of()));
        Instant start = Instant.parse("2026-03-08T05:00:00Z"); // 当地 00:00（EST, UTC-5）
        assertThat(cal.between(start, Instant.parse("2026-03-08T10:00:00Z"))).isEqualTo(Duration.ofHours(5));
        assertThat(cal.plus(start, Duration.ofHours(5))).isEqualTo(Instant.parse("2026-03-08T10:00:00Z")); // 当地 06:00 EDT
    }

    @Test
    void zeroAmountIsTheStartItselfAndWeekendIsNotWorking() {
        BusinessCalendar cal = new BusinessCalendar(new CalendarDef("utc", "UTC", weekdays("09:00", "18:00", MON_FRI), Set.of()));
        Instant saturday = Instant.parse("2026-10-03T10:00:00Z");
        assertThat(cal.plus(saturday, Duration.ZERO)).isEqualTo(saturday);
        assertThat(cal.isWorking(saturday)).isFalse();
        assertThat(cal.between(saturday, Instant.parse("2026-10-04T23:00:00Z"))).isZero();
    }

    @Test
    void rejectsBrokenCalendarsInsteadOfLoopingForever() {
        assertThatThrownBy(() -> new BusinessCalendar(new CalendarDef("x", "Mars/Olympus", weekdays("09:00", "18:00", MONDAY), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar(new CalendarDef("x", "UTC", List.of(), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar(new CalendarDef("x", "UTC",
                List.of(WorkingSlot.parse(MONDAY, "09:00", "12:00"), WorkingSlot.parse(MONDAY, "11:00", "13:00")), Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkingSlot.parse(SATURDAY, "18:00", "09:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkingSlot.parse(SATURDAY, "09:00", "24:30")).isInstanceOf(IllegalArgumentException.class);

        // 只有周一营业、而十年的周一全是节假日 → 抛错而不是死循环
        Set<LocalDate> allMondays = Stream.iterate(LocalDate.of(2026, 10, 5), d -> d.plusWeeks(1)).limit(600).collect(java.util.stream.Collectors.toSet());
        BusinessCalendar never = new BusinessCalendar(new CalendarDef("x", "UTC", weekdays("09:00", "18:00", MONDAY), allMondays));
        assertThatThrownBy(() -> never.plus(Instant.parse("2026-10-05T00:00:00Z"), Duration.ofHours(1))).isInstanceOf(IllegalStateException.class);
    }
}
