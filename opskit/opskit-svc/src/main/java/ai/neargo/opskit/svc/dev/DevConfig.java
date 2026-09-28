package ai.neargo.opskit.svc.dev;

import ai.neargo.opskit.platform.core.app.DirectoryAppService;
import ai.neargo.opskit.platform.core.app.FederationAppService;
import ai.neargo.opskit.platform.core.app.MemberAttrAppService;
import ai.neargo.opskit.platform.core.app.TenancyAppService;
import ai.neargo.opskit.process.core.app.InstanceAppService;
import ai.neargo.opskit.process.core.app.ProcessDefinitionAppService;
import ai.neargo.opskit.process.core.app.ProcessQueryService;
import ai.neargo.opskit.process.core.app.TaskAppService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 本地联调装配（仅 local 配置档）：测试身份提供方 + 种子。生产镜像不激活这个配置档。 */
@Profile("local")
@Configuration(proxyBeanMethods = false)
public class DevConfig {

    @Bean
    DevIdentityProvider devIdentityProvider(@Value("${opskit.dev.key-file:${java.io.tmpdir}/opskit-dev-idp-key.json}") String keyFile, Clock clock) {
        return new DevIdentityProvider(Path.of(keyFile), clock);
    }

    @Bean
    DevSeed devSeed(TenancyAppService tenancy, FederationAppService federation, DirectoryAppService directory, MemberAttrAppService attrs,
            ProcessDefinitionAppService definitions, InstanceAppService instances, TaskAppService tasks, ProcessQueryService queries,
            @Value("${opskit.dev.jwks-url:http://localhost:${server.port:8090}/dev/jwks.json}") String jwksUrl) {
        return new DevSeed(tenancy, federation, directory, attrs, definitions, instances, tasks, queries, jwksUrl);
    }

    @Bean
    ApplicationRunner devSeedRunner(DevSeed seed) {
        return args -> seed.run();
    }
}
