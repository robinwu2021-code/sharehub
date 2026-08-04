package ai.neargo.sharehub.api.remote;

import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Port 远程实现的装配（ADR-017 §5.2 的落点）。
 *
 * <h3>⚠️ 为什么是 {@code @AutoConfiguration} 而不是给 Remote* 打 {@code @Component}</h3>
 * {@code @ConditionalOnMissingBean} 作用在**被组件扫描的 {@code @Component}** 上是
 * <b>不可靠的</b> —— Spring 官方明确说明：条件在该类被扫描到的那一刻求值，
 * 而扫描顺序无保证。若远程实现恰好先被扫到，本地实现就再也装不进去，
 * <b>单体形态会静默走 HTTP</b>：能跑、但每次调用都绕一圈网络，且没有任何报错。
 *
 * <p>自动配置在**全部用户 bean 定义之后**处理，此时「本地实现在不在」已成定局，
 * 条件判断才是确定的。这是 Spring Boot 文档指定的用法。
 *
 * <h3>装配语义</h3>
 * <ul>
 *   <li>单体：classpath 上有 {@code LocalSiteQuery} 等 → 本地实现胜出，远程 bean 不创建；</li>
 *   <li>微服务：没有本地实现 → 装配远程实现，走 HTTP。</li>
 * </ul>
 * <b>装配由 classpath 决定，不读 {@code sharehub.deploy.mode}</b> —— 配置写错不会导致装配错误。
 *
 * <h3>已知局限（不藏着）</h3>
 * <ul>
 *   <li><b>✅ 凭证透传已实现</b>：{@link RemoteCaller} 带上调用方的 {@code Bearer} token，
 *       对端用自己的 TokenStore 反查得到同一身份与数据范围。
 *       <b>透传的是 token 而非 X-User-Id/X-Roles</b> —— 后者等于让能访问 {@code /internal/**}
 *       的任何人冒充任意身份，与本项目「只认 token 反查权限」的红线直接冲突。
 *       <b>仍需注意</b>：后台任务（对账/投递器）没有调用方身份，此时不带 token，
 *       依赖会话的 Port 在那种上下文下结果不正确。</li>
 *   <li><b>无重试与熔断</b>：跨进程调用会失败，当前直接抛异常。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(RemoteProperties.class)
public class RemotePortsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestClient sharehubRemoteRestClient() {
        return RestClient.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public RemoteCaller remoteCaller(RestClient sharehubRemoteRestClient, RemoteProperties props) {
        return new RemoteCaller(sharehubRemoteRestClient, props);
    }

    @Bean
    @ConditionalOnMissingBean(SiteQueryPort.class)
    public SiteQueryPort remoteSiteQuery(RemoteCaller caller) {
        return new RemotePorts.RemoteSiteQuery(caller);
    }

    @Bean
    @ConditionalOnMissingBean(TelemetryQueryPort.class)
    public TelemetryQueryPort remoteTelemetryQuery(RemoteCaller caller) {
        return new RemotePorts.RemoteTelemetryQuery(caller);
    }

    @Bean
    @ConditionalOnMissingBean(DeviceOwnershipPort.class)
    public DeviceOwnershipPort remoteDeviceOwnership(RemoteCaller caller) {
        return new RemotePorts.RemoteDeviceOwnership(caller);
    }

    @Bean
    @ConditionalOnMissingBean(CabinetQueryPort.class)
    public CabinetQueryPort remoteCabinetQuery(RemoteCaller caller) {
        return new RemotePorts.RemoteCabinetQuery(caller);
    }
}
