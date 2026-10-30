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
 * 让用例在「合同 / 勘测门禁强制」下跑（V174 之前的口径）。
 *
 * <p>V174 把一期默认改成跳过合同与勘测（{@code site.venue_gate.enabled=0}，用户 2026-10-28：平台运营直接操作）。
 * 这些用例验证的是**无合同、未勘测时门禁拦得住**—— 开关打开时规则仍然生效、仍要守住，
 * 所以不改断言，而是在用例前显式打开，跑完还原成一期默认值（写法同 {@link LegacyShareParams}）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VenueGateOn.Extension.class)
public @interface VenueGateOn {

    class Extension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {

        @Override
        public void beforeAll(ExtensionContext ctx) {
            set(ctx, "1");
        }

        @Override
        public void beforeEach(ExtensionContext ctx) {
            set(ctx, "1");
        }

        @Override
        public void afterAll(ExtensionContext ctx) {
            set(ctx, "0");
        }

        static void set(ExtensionContext ctx, String enabled) {
            JdbcTemplate jdbc = SpringExtension.getApplicationContext(ctx).getBean(JdbcTemplate.class);
            jdbc.update("UPDATE sys_param SET value = ? WHERE tenant_id = 'MAIN' AND param_key = 'site.venue_gate.enabled'", enabled);
        }
    }
}
