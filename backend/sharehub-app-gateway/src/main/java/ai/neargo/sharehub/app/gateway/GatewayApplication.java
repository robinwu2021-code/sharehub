package ai.neargo.sharehub.app.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * device-gateway 微服务形态的启动类（ADR-017 §5.1）。
 *
 * <p><b>零业务代码</b>——只有 main + 扫描范围。业务全在 `sharehub-svc-gateway`，
 * 与单体形态共用同一份实现。
 *
 * <p><b>扫描范围显式限定到 `gw` 与 `common`</b>：不写的话默认从本类所在包往下扫，
 * 扫不到 svc 的 bean；写成整个 `ai.neargo.sharehub` 又会在单体形态下重复装配。
 * 这是「app-* 只负责装配」这句话的具体含义。
 */
@SpringBootApplication
// Mapper 扫描**必须限定到本服务的包**。单体用的是 @MapperScan("ai.neargo.sharehub") 一网打尽，
// 微服务形态照抄会扫到别的服务的 mapper —— 那些 mapper 对应的表不在本服务的库里，
// 拆库后会在运行期报表不存在。**装配范围是 app 的职责，不是 svc 的**。
@MapperScan(basePackages = {
        "ai.neargo.sharehub.gw.mapper",
        "ai.neargo.sharehub.common.event.mapper",   // outbox：每个 app 都要，它是基础设施
})
@ComponentScan(basePackages = {
        "ai.neargo.sharehub.app.gateway",
        "ai.neargo.sharehub.gw",
        "ai.neargo.sharehub.common",
})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
