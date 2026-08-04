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

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler)); // 数据权限先于分页
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
