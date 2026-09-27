package ai.neargo.sharehub.config;

import ai.neargo.common.data.scope.DataScopeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（P4 持久化）：数据权限 + 分页 + 乐观锁拦截器；扫描各域 mapper（嵌套接口）。
 * 拦截器顺序遵循 MP 建议：（租户→）数据权限→分页。租户行级隔离 MVP 暂不启用（单租户 MAIN）。
 */
@Configuration
@MapperScan(basePackages = "ai.neargo.sharehub",
        markerInterface = com.baomidou.mybatisplus.core.mapper.BaseMapper.class)
public class MybatisPlusConfig {

    /**
     * 注入给注解 SQL 的时区变量：{@code ${bizOffset}}（业务时区）与 {@code ${storedOffset}}（存储时区 = JVM 默认）。
     *
     * <p>报表与运营概览按<b>业务日</b>切日（2026-09-28 拍板：统一按业务时区）。此前按 UTC 切 ——
     * {@code LEFT(rent_end_at, 10)} 取的是 UTC 日期，迪拜 00:00–04:00 的单记到前一天，
     * 而券、合同、分润归属已经按业务日历 —— 同一张单在不同模块里属于不同的「那一天」。
     *
     * <p>用 MyBatis 配置变量而不是逐个方法加参数：十几条查询、一个口径，只在一处算。
     * 偏移取自 {@code sharehub.biz-zone}（与 {@code BizClock} 同一个配置），<b>启动时定格</b> ——
     * 对无夏令时的时区（Asia/Dubai）是精确的；配了有夏令时的时区会在启动日志里告警。
     * 用数字偏移而不是时区名：{@code CONVERT_TZ} 认数字偏移不需要装时区表。
     */
    @Bean
    public com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer bizZoneSqlVariables(
            @org.springframework.beans.factory.annotation.Value("${sharehub.biz-zone:Asia/Dubai}") String bizZone) {
        java.time.ZoneId biz = java.time.ZoneId.of(bizZone);
        if (!biz.getRules().isFixedOffset() && !biz.getRules().getTransitions().isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(MybatisPlusConfig.class).warn(
                    "业务时区 {} 有夏令时，报表切日的偏移在启动时定格为 {} —— 跨越切换日后需重启，或改报表查询按时刻换算", bizZone, offsetOf(biz));
        }
        String bizOffset = offsetOf(biz);
        String storedOffset = offsetOf(java.time.ZoneId.systemDefault());
        return props -> {
            java.util.Properties p = props.getConfigurationProperties() == null
                    ? new java.util.Properties() : props.getConfigurationProperties();
            p.setProperty("bizOffset", bizOffset);
            p.setProperty("storedOffset", storedOffset);
            props.setConfigurationProperties(p);
        };
    }

    /** {@code +04:00} 形式；UTC 的 id 是 {@code Z}，CONVERT_TZ 不认，换成 {@code +00:00}。 */
    static String offsetOf(java.time.ZoneId zone) {
        String id = zone.getRules().getOffset(java.time.Instant.now()).getId();
        return "Z".equals(id) ? "+00:00" : id;
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler)); // 数据权限先于分页
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
