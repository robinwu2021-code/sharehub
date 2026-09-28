package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.time.BizClock;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务时区可由管理员改（2026-09-28 裁决，见 {@code docs/technical/方案-业务时区可配置.md}）。
 *
 * <h2>这三条各守什么</h2>
 * <ol>
 *   <li><b>改完立刻生效</b> —— 钉死「不缓存」。将来有人顺手加个 TTL 缓存，这条会红。
 *       为什么在意：TTL 会制造「改了但还没生效」的窗口，而时区缺陷本来就
 *       每天只在几个小时里成立，两者叠加几乎不可能被查出来。</li>
 *   <li><b>非法值写入时就被拒</b> —— 而不是读取时静默回落。
 *       后者会让界面显示「已保存」而实际没生效。</li>
 *   <li><b>没配时回落启动期默认值</b> —— 保证「参数表为空」与改造之前行为一致。</li>
 * </ol>
 */
class BizTimezoneConfigTest extends ApiTestSupport {

    private static final String KEY = "biz.timezone";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BizClock clock;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM sys_param WHERE param_key=?", KEY);
    }

    @Test
    @DisplayName("★ 改完立刻生效 —— 不许缓存（TTL 缓存会造出「改了还没生效」的窗口）")
    void a_change_takes_effect_immediately() {
        String admin = login("ADMIN");

        /*
         * **改两次**，断言的是第二次。
         *
         * 只改一次抓不住缓存：第一次读到的是兜底值（参数表里还没有这一行），
         * 一个只在「取到值」时才写缓存的实现根本没被热身，改完再读自然是新的 ——
         * 实测过：那样写时，负对照红的是下面第三条而不是这一条。
         * 先写一次让缓存必定是热的，再改第二次，这条才真的在守「不缓存」。
         */
        assertThat(post("/api/platform/sys-params",
                Map.of("paramKey", KEY, "value", "Asia/Dubai", "label", "业务时区"), admin).status)
                .isEqualTo(200);
        assertThat(clock.zone().getId()).as("第一次写入就没生效").isEqualTo("Asia/Dubai");

        /*
         * ⚠️ 目标时区必须与 Asia/Dubai **同偏移**（Asia/Muscat 也是 UTC+4）。
         *
         * 第一版用的是 Pacific/Kiritimati（UTC+14），结果**把别的用例跑红了**：
         * ReportReadModelTest 的 GMV 对不上、SiteAssetFlowTest 的进场费折算差一天 ——
         * 它们单独跑都是绿的。原因是这条用例改的是**全局配置**，而整个套件共用
         * 一个 Spring 上下文；@AfterEach 删行也来不及，别的用例在它跑完之前就读到了。
         *
         * 改用同偏移的时区：ZoneId 不同（足以验证「配置真的生效了」），
         * 而任何按业务日/业务月分桶的断言都不受影响。
         */
        String target = "Asia/Muscat";
        assertThat(post("/api/platform/sys-params/" + KEY,
                Map.of("paramKey", KEY, "value", target, "label", "业务时区"), admin).status)
                .isEqualTo(200);

        assertThat(clock.zone().getId())
                .as("第二次改完没有立刻生效 —— 中间是不是加了缓存？")
                .isEqualTo(target);

        // 收尾即刻恢复，不等 @AfterEach —— 少一个窗口就少一次污染别的用例的机会
        jdbc.update("DELETE FROM sys_param WHERE param_key=?", KEY);
    }

    @Test
    @DisplayName("★ 非法时区写入时就被拒 —— 不能存进去再在读取处兜底")
    void an_invalid_zone_is_rejected_on_write() {
        String admin = login("ADMIN");
        ZoneId before = clock.zone();

        Resp r = post("/api/platform/sys-params",
                Map.of("paramKey", KEY, "value", "Asia/Dubay", "label", "业务时区"), admin);
        assertThat(r.status).as("拼错的时区被存下来了：%s", r.body).isEqualTo(400);

        /*
         * 光断言 400 不够 —— 要确认它**真的没存进去**。
         * 存进去再靠读取处回落的话，界面会显示「已保存」而系统仍按旧时区算，
         * 那正是这条用例要防的形状。
         */
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_param WHERE param_key=?", Integer.class, KEY))
                .as("被拒之后不该留下这一行").isZero();
        assertThat(clock.zone()).as("时区不该被动过").isEqualTo(before);
    }

    @Test
    @DisplayName("没配时回落启动期默认值 —— 参数表为空要与改造之前行为一致")
    void it_falls_back_when_the_param_is_absent() {
        jdbc.update("DELETE FROM sys_param WHERE param_key=?", KEY);
        assertThat(clock.zone().getId())
                .as("参数表里没有这一行时，应当用 sharehub.biz-zone 的值")
                .isEqualTo("Asia/Dubai");
    }
}
