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
 * 让用例在「商家自助注册入口打开」下跑。
 *
 * <p>V155 起一期默认关闭自助入口（需求方案 D2），三个免鉴权端点一律 404。
 * 自助流程本身（OTP、限流、同手机号至多一张在途、驳回原文回显）二期还要用，仍要守住，
 * 所以这些用例显式打开，跑完还原成关闭。「关闭时 404」由 {@code Phase1ShareDefaultsTest} 守。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(SelfServiceApplyOpen.Extension.class)
public @interface SelfServiceApplyOpen {

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

        private static void set(ExtensionContext ctx, String value) {
            SpringExtension.getApplicationContext(ctx).getBean(JdbcTemplate.class).update(
                    "UPDATE sys_param SET value = ? WHERE tenant_id = 'MAIN' AND param_key = 'agent.apply.self_service_enabled'",
                    value);
        }
    }
}
