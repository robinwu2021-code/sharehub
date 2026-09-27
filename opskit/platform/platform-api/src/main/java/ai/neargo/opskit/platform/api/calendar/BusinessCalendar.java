package ai.neargo.opskit.platform.api.calendar;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 营业时间推算：SLA 的「只算营业时间」、定时门的营业时长都靠它（21/02 §8.5）。
 *
 * <p>一律按日历时区逐日走营业时段：时段边界先在当地时间构造、再换算成时刻，所以夏令时切换日按真实经过的时长计
 * （如纽约 3 月跳时那天 00:00–06:00 实际只有 5 小时）。存进库、比较、调度用的都是换算后的 {@link Instant}。
 */
public final class BusinessCalendar {

    /** 连续十年找不到足够的营业时间 = 日历配错了（比如节假日覆盖了所有工作日），不能无限循环。 */
    private static final int MAX_DAYS = 3660;

    private final ZoneId zone;
    private final Map<DayOfWeek, List<WorkingSlot>> slotsByDay = new EnumMap<>(DayOfWeek.class);
    private final Set<LocalDate> holidays;

    public BusinessCalendar(CalendarDef def) {
        try {
            this.zone = ZoneId.of(def.zone());
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("invalid calendar zone: " + def.zone(), e);
        }
        if (def.weekly().isEmpty()) {
            throw new IllegalArgumentException("calendar " + def.code() + " has no working slots");
        }
        for (WorkingSlot s : def.weekly()) {
            slotsByDay.computeIfAbsent(s.day(), d -> new ArrayList<>()).add(s);
        }
        slotsByDay.values().forEach(list -> {
            list.sort(Comparator.comparingInt(WorkingSlot::fromMinute));
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i).fromMinute() < list.get(i - 1).toMinute()) {
                    throw new IllegalArgumentException("overlapping slots on " + list.get(i).day());
                }
            }
        });
        this.holidays = def.holidays();
    }

    /** 从 {@code start} 起累计 {@code amount} 的营业时长之后的时刻。起点不在营业时间内时，从下一个营业时段开始算。 */
    public Instant plus(Instant start, Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.isZero()) {
            return start;
        }
        long remaining = amount.toMillis();
        LocalDate date = start.atZone(zone).toLocalDate();
        for (int i = 0; i < MAX_DAYS; i++, date = date.plusDays(1)) {
            for (Window w : windows(date)) {
                Instant from = w.from.isBefore(start) ? start : w.from;
                if (!from.isBefore(w.to)) {
                    continue;
                }
                long available = Duration.between(from, w.to).toMillis();
                if (remaining <= available) {
                    return from.plusMillis(remaining);
                }
                remaining -= available;
            }
        }
        throw new IllegalStateException("no enough business time within " + MAX_DAYS + " days");
    }

    /** {@code [from, to)} 之间的营业时长 —— 用于「已用时长」「暂停期间的营业时长」。 */
    public Duration between(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            return Duration.ZERO;
        }
        long total = 0;
        LocalDate date = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        for (int i = 0; i < MAX_DAYS && !date.isAfter(last); i++, date = date.plusDays(1)) {
            for (Window w : windows(date)) {
                Instant a = w.from.isBefore(from) ? from : w.from;
                Instant b = w.to.isAfter(to) ? to : w.to;
                if (a.isBefore(b)) {
                    total += Duration.between(a, b).toMillis();
                }
            }
        }
        return Duration.ofMillis(total);
    }

    public boolean isWorking(Instant t) {
        LocalDate date = t.atZone(zone).toLocalDate();
        return windows(date).stream().anyMatch(w -> !t.isBefore(w.from) && t.isBefore(w.to));
    }

    private List<Window> windows(LocalDate date) {
        if (holidays.contains(date)) {
            return List.of();
        }
        List<Window> out = new ArrayList<>();
        for (WorkingSlot s : slotsByDay.getOrDefault(date.getDayOfWeek(), List.of())) {
            out.add(new Window(at(date, s.fromMinute()), at(date, s.toMinute())));
        }
        return out;
    }

    /** 当地日期 + 当天第几分钟 → 时刻。1440 = 次日 00:00。夏令时跳过的时刻由 ZonedDateTime 顺延。 */
    private Instant at(LocalDate date, int minute) {
        if (minute == WorkingSlot.END_OF_DAY) {
            return date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant();
    }

    private record Window(Instant from, Instant to) {
    }
}
