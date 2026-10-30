package ai.neargo.sharehub.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给用例要划拨资产的种子商家补上核心资料（位置、行业）。
 *
 * <p>V157 起位置与行业不齐的商家不能被划入资产（需求方案 §六，AC-04），而种子商家建档时还没有这两项。
 * 这些用例验的是划拨本身（级联、出箱事件），不是门禁 —— 门禁由 {@code Phase1MerchantProfileTest} 守。
 *
 * <p>**直接写库而不走保存接口**：走接口会顺带建默认站点并划给商家，改掉用例里的资产计数。
 * 只补空值（COALESCE），不覆盖别的用例或运营已填的值。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MerchantCoreProfile.Extension.class)
public @interface MerchantCoreProfile {

    /** 要补资料的商家编号。 */
    String[] value();

    class Extension implements BeforeAllCallback {
        @Override
        public void beforeAll(ExtensionContext ctx) {
            JdbcTemplate jdbc = SpringExtension.getApplicationContext(ctx).getBean(JdbcTemplate.class);
            for (String agentNo : ctx.getRequiredTestClass().getAnnotation(MerchantCoreProfile.class).value()) {
                jdbc.update("UPDATE agt_agent SET address = COALESCE(address, '测试地址 Dubai Mall'), "
                        + "lng = COALESCE(lng, 55.279400), lat = COALESCE(lat, 25.197200), "
                        + "industry = COALESCE(industry, 'RETAIL') WHERE agent_no = ?", agentNo);
            }
        }
    }
}
