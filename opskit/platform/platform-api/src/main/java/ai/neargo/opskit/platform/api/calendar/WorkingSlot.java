package ai.neargo.opskit.platform.api.calendar;

import java.time.DayOfWeek;
import java.util.Objects;

/**
 * 一周里某一天的一段营业时间，以「当天第几分钟」表示：0 = 00:00，1440 = 24:00（当天结束）。
 * 跨午夜的班次拆成两段（周一 22:00–24:00 + 周二 00:00–06:00），计算时两段首尾相接、连续计时。
 */
public record WorkingSlot(DayOfWeek day, int fromMinute, int toMinute) {

    public static final int END_OF_DAY = 24 * 60;

    public WorkingSlot {
        Objects.requireNonNull(day, "day");
        if (fromMinute < 0 || toMinute > END_OF_DAY || fromMinute >= toMinute) {
            throw new IllegalArgumentException("slot must satisfy 0 <= from < to <= 1440, got " + fromMinute + "-" + toMinute);
        }
    }

    /** {@code parse(MONDAY, "09:00", "18:00")}；结束时刻允许写 {@code 24:00}。 */
    public static WorkingSlot parse(DayOfWeek day, String from, String to) {
        return new WorkingSlot(day, minutes(from), minutes(to));
    }

    private static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("time must be HH:mm, got " + hhmm);
        }
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3));
        if (m > 59 || h > 24 || (h == 24 && m != 0)) {
            throw new IllegalArgumentException("invalid time " + hhmm);
        }
        return h * 60 + m;
    }
}
