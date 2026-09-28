package ai.neargo.opskit.svc.bootstrap;

import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 只在声明了引导文件位置的配置档（prod）生效；以 BeanFactoryPostProcessor 执行，赶在连数据库之前失败。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "opskit.deploy.bootstrap-file")
public class BootstrapFileGuardConfig {

    @Bean
    static BeanFactoryPostProcessor bootstrapFileGuard(Environment env) {
        return beanFactory -> BootstrapFileGuard.check(Path.of(env.getRequiredProperty("opskit.deploy.bootstrap-file")),
                env.getProperty("opskit.deploy.require-bootstrap", Boolean.class, false),
                env.getProperty("opskit.bootstrap.enabled", Boolean.class, false));
    }
}
