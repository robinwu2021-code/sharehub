package ai.neargo.sharehub.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * 测试库的迁移策略：**migrate 之前先 repair**。
 *
 * <p>开发期迁移文件天天在改（多会话并行时尤其频繁）。改一次，已经应用过它的库就校验不过：
 * {@code Migration checksum mismatch for migration version 50}。这个报错埋在一长串
 * bean 创建链的末尾，**指不到「去重建测试库」这件事上** —— 本次实测，全量 203 个测试
 * 全部倒在这里，而代码一行问题都没有。
 *
 * <p>{@code repair()} 把历史表里的校验和刷成当前文件的值，再正常 migrate。
 * 之所以敢这么做，是因为**测试库本来就是可抛弃的**：它每次从空库跑全部迁移，
 * 没有任何需要保留的数据；校验和存在的意义（防止有人改动已上线的迁移）在这里不适用。
 *
 * <p>只在 test 源码树里，且测试库名被限定在 {@code test_} 前缀下 ——
 * 开发库与生产库绝不会走到这段。
 *
 * <p>（试过 {@code spring.flyway.clean-on-validation-error}，新版 Flyway 已不生效。）
 */
// 用 @Configuration 而不是 @TestConfiguration：后者**刻意不被组件扫描**
// （要显式 @Import 才生效），那正是它与前者的区别。本类在 test 源码树的
// ai.neargo.sharehub 包下，@SpringBootApplication 的扫描根覆盖到它，自动生效。
@Configuration(proxyBeanMethods = false)
public class TestFlywayStrategy {

    @Bean
    FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
