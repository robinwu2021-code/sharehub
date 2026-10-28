package ai.neargo.sharehub.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 让用例在「场地方参与分成 + 含税实收作基数」下跑（V155 之前的口径）。
 *
 * <p>V155 把一期默认改成了场地方不分成、基数取税前（需求方案 D9 / D10）。
 * 这些用例验证的是**场地方分成、保底补差、按含税金额核对**这类逻辑 —— 开关打开时它们仍然生效、仍要守住，
 * 所以不改断言，而是在每条用例前显式打开旧口径，跑完还原成一期默认值。
 *
 * <p>测试库是多个类共享的累积库：不还原的话，后面的类会在错误的口径下跑，且失败原因指不到这里。
 * 一期默认口径本身由 {@code Phase1ShareDefaultsTest} 守。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(LegacyShareParams.Extension.class)
public @interface LegacyShareParams {

    /**
     * 类级打开、类级还原：多数用例是 PER_CLASS，造数在 {@code @BeforeAll} 里（扩展的 beforeAll 先于它执行）。
     * beforeEach 再设一次，防同类里某条用例自己改了参数。
     */
    class Extension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {

        @Override
        public void beforeAll(ExtensionContext ctx) {
            set(ctx, "1", "GROSS");
        }

        @Override
        public void beforeEach(ExtensionContext ctx) {
            set(ctx, "1", "GROSS");
        }

        @Override
        public void afterAll(ExtensionContext ctx) {
            set(ctx, "0", "PRE_TAX");
        }

        private static void set(ExtensionContext ctx, String venueEnabled, String baseMode) {
            JdbcTemplate jdbc = SpringExtension.getApplicationContext(ctx).getBean(JdbcTemplate.class);
            jdbc.update("UPDATE sys_param SET value = ? WHERE tenant_id = 'MAIN' AND param_key = 'finance.share.venue_enabled'", venueEnabled);
            jdbc.update("UPDATE sys_param SET value = ? WHERE tenant_id = 'MAIN' AND param_key = 'finance.share.base_mode'", baseMode);
        }
    }
}
