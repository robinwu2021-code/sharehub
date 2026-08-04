package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双形态装配的不变式（ADR-017 §5.2）。
 *
 * <p><b>这是整套「一套代码两种部署」的正确性支点</b>：
 * 单体形态下本地实现必须胜出。若远程实现被误装配，系统**照样能跑** ——
 * 每次调用绕一圈 HTTP，功能正常、测试全绿、没有任何报错，
 * 只是性能莫名其妙地差，且在没有远程地址配置时才会突然崩。
 * <b>这类错误不会自己暴露，只能靠断言锁住。</b>
 *
 * <p>为什么容易装错：`@ConditionalOnMissingBean` 作用在被组件扫描的 `@Component` 上
 * 是不可靠的（扫描顺序无保证）。远程实现因此放在 `@AutoConfiguration` 里 ——
 * 自动配置在全部用户 bean 之后处理，条件判断才是确定的。本类锁死这个结果。
 */
class PortWiringTest extends ApiTestSupport {

    @Autowired
    private SiteQueryPort siteQueryPort;

    @Autowired
    private TelemetryQueryPort telemetryQueryPort;

    @Autowired
    private DeviceOwnershipPort deviceOwnershipPort;

    /** 单体形态：三个 Port 全部装配本地实现，一个远程都不许有。 */
    @Test
    void monolith_wires_local_implementations_not_remote() {
        assertThat(siteQueryPort.getClass().getSimpleName())
                .as("单体下装了远程实现 → 每次查站点都绕一圈 HTTP，且功能正常不会报错")
                .isEqualTo("LocalSiteQuery");

        assertThat(telemetryQueryPort.getClass().getSimpleName())
                .isEqualTo("LocalTelemetryQuery");

        assertThat(deviceOwnershipPort.getClass().getSimpleName())
                .isEqualTo("LocalDeviceOwnership");
    }

    /** 本地实现必须真能用 —— 只断言类型不够，装对了但调不通同样是坏的。 */
    @Test
    void local_port_actually_works() {
        assertThat(siteQueryPort.briefsByNos(java.util.List.of("ST300")))
                .as("本地 Port 应能取到数据")
                .isNotNull();
    }
}
