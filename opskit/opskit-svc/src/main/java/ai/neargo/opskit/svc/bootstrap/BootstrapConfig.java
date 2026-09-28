package ai.neargo.opskit.svc.bootstrap;

import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** opskit.bootstrap.enabled=true 时每次启动跑一遍引导（幂等）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BootstrapProperties.class)
@ConditionalOnProperty(name = "opskit.bootstrap.enabled", havingValue = "true")
public class BootstrapConfig {

    @Bean
    OpsKitBootstrap opsKitBootstrap(BootstrapProperties props, TenancyAppService tenancy, FederationAppService federation, DirectoryAppService directory) {
        return new OpsKitBootstrap(props, tenancy, federation, directory);
    }

    @Bean
    ApplicationRunner opsKitBootstrapRunner(OpsKitBootstrap bootstrap) {
        return args -> bootstrap.run();
    }
}
