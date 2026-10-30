package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixedBizClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「今日」这类统计窗口的日界，按**业务日历**切，且要**换算到存储时区**。
 *
 * <h2>为什么不是直接 {@code clock.today().atStartOfDay()}</h2>
 * {@code notify_log.created_at} 是审计列，由 {@code LocalDateTime.now()} 写入 ——
 * **服务器 JVM 时区**（与 {@code updated_at} 同一口径，这是对的，不该改存储语义）。
 *
 * <p>拿「业务日的零点」直接去比「服务器时区的时间戳」，得到的窗口是**不自洽**的：
 * 迪拜 9-30 23:00 发出的通知（业务日 9-30）会被算进「10-01 今日」，
 * 因为它的服务器时间戳已经是 10-01。
 *
 * <p>换句话说：**改之前至少是自洽的**（服务器日 vs 服务器时间戳），只是那个「日」
 * 不是业务日；直接替换会把自洽的偏差换成不自洽的偏差 —— **比不改更糟**。
 * 所以要走 {@code BizClock#startOfDayAsStored}，把日界折算到存储时区再比。
 *
 * <h2>本用例与时刻无关</h2>
 * 时钟钉在 <b>UTC 2030-06-30T20:30</b>：业务时区（+4）已是 2030-07-01 00:30。
 * 于是业务日界 = 迪拜 2030-07-01 00:00，换算到存储时区就是一个确定的时刻。
 * 两条夹具行分别落在它**两侧几分钟**，所以无论今天真实日期是哪天都成立。
 */
@Import(FixedBizClock.class)
class TodayWindowOnBizCalendarTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FixedBizClock.MutableClock clock;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM notify_log WHERE log_no LIKE 'NL-TZ-%'");
    }

    @Test
    @DisplayName("★ 业务日界之前那条不该进「今日」——看服务端统计的增量，不是自己再算一遍")
    void the_today_window_starts_at_the_business_day_boundary() {
        clock.at = FixedBizClock.JUST_AFTER_BIZ_MIDNIGHT;      // 迪拜 2030-07-01 00:30

        // 业务日 2030-07-01 的起点，表达成存储时区（= 本进程 JVM 时区）的时刻
        LocalDateTime boundary = java.time.LocalDate.of(2030, 7, 1)
                .atStartOfDay(ZoneId.of("Asia/Dubai"))
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();

        String admin = login("ADMIN");
        long base = sentToday(admin);

        /*
         * —— 为什么看增量，而不是自己再算一遍日界 ——
         *
         * 第一版写成「自己按正确公式算一个窗口，再数我的两条行」—— 那两个断言算的是
         * 同一个式子，**无论服务端怎么实现都成立**，是一条永远不会红的用例。
         * 要观测的是**服务端的口径**，所以只能拿它的输出做对比：
         * 插一条业务日界之前的，它的 sentToday 不该动。
         */
        seed("NL-TZ-BEFORE", boundary.minusMinutes(5), "SENT");   // 业务日仍是 06-30
        assertThat(sentToday(admin) - base)
                .as("业务日界**之前** 5 分钟那条被算进了今日 —— 日界没折算到存储时区")
                .isZero();

        seed("NL-TZ-AFTER", boundary.plusMinutes(5), "SENT");     // 业务日已是 07-01
        assertThat(sentToday(admin) - base)
                .as("业务日界**之后** 5 分钟那条没被算进今日")
                .isEqualTo(1);
    }

    /*
     * 旧实现（LocalDate.now().atStartOfDay()）在这条用例下必红：
     * 它的日界是「真实今天的零点」，而两条夹具都落在 2030-07-01 这个**未来**时刻，
     * 于是第一条插进去 sentToday 就 +1，上面第一个断言当场不成立。
     * 与真实日期无关 —— 只要今天不是 2030-07-01 之后。
     */

    private long sentToday(String admin) {
        return get("/api/platform/notify-logs/stats", admin).okData().path("sentToday").asLong();
    }

    private void seed(String logNo, LocalDateTime createdAt, String status) {
        jdbc.update("""
                INSERT INTO notify_log (log_no, tenant_id, channel, target, scene, status, cost, currency, created_at)
                VALUES (?, 'MAIN', 'SMS', '971****0000', 'TEST', ?, 0.10, 'AED', ?)
                """, logNo, status, createdAt);
    }
}
