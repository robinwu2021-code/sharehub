package ai.neargo.sharehub.inv.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 库存服务寻址的默认装配：没有别的 {@link InventoryEndpoint}（如服务发现模块提供的）时用固定地址。 */
@Configuration
public class InventoryEndpointConfig {

    @Bean
    @ConditionalOnMissingBean(InventoryEndpoint.class)
    public InventoryEndpoint staticInventoryEndpoint(@Value("${sharehub.inventory.base-url:}") String baseUrl) {
        return new StaticInventoryEndpoint(baseUrl);
    }
}
