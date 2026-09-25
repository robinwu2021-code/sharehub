package ai.neargo.sharehub.alarm.engine;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站点营业时间（{@code loc_site.open_hours}）的解析与计时。
 *
 * <p>格式：{@code 10:00-22:00}，多段用逗号分隔（{@code 10:00-14:00,17:00-22:00}），跨零点写成 {@code 22:00-02:00}。
 * 空或解析不了 → 按 24 小时营业（宁可多报，站点概览会提示补配营业时间）。
 */
public final class BusinessHours {

    private BusinessHours() {
    }

    record Window(LocalTime from, LocalTime to) {
        boolean contains(LocalTime t) {
            return from.isBefore(to) || from.equals(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        }
    }

    static List<Window> parse(String openHours) {
        List<Window> out = new ArrayList<>();
        if (openHours == null || openHours.isBlank()) return out;
        for (String seg : openHours.split("[,，;]")) {
            String[] p = seg.trim().split("-");
            if (p.length != 2) return List.of();
            try {
                out.add(new Window(LocalTime.parse(pad(p[0].trim())), LocalTime.parse(pad(p[1].trim()))));
            } catch (RuntimeException e) {
                return List.of();
            }
        }
        return out;
    }

    private static String pad(String t) {
        if (t.equals("24:00")) return "23:59";
        return t.length() == 4 ? "0" + t : t;
    }

    public static boolean isOpen(String openHours, LocalDateTime at) {
        List<Window> ws = parse(openHours);
        if (ws.isEmpty()) return true;
        LocalTime t = at.toLocalTime();
        return ws.stream().anyMatch(w -> w.contains(t));
    }

    /** [from, to) 中落在营业时间内的分钟数。调用方已把区间截到 ≤5 分钟，逐分钟判足够。 */
    public static int openMinutes(String openHours, LocalDateTime from, LocalDateTime to) {
        int n = 0;
        for (LocalDateTime t = from; t.isBefore(to); t = t.plusMinutes(1)) {
            if (isOpen(openHours, t)) n++;
        }
        return n;
    }
}
