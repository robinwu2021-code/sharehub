package ai.neargo.sharehub.svc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 「调哪里」—— 服务名 → 基址。与 {@link InternalClient}（「怎么调」）分开，
 * 使换地址是改配置、换调用方式是改代码，两件事互不牵连。
 *
 * <p>配置形如：
 * <pre>
 * sharehub:
 *   services:
 *     targets:
 *       SHAREHUB: http://127.0.0.1:8082
 *       GATEWAY:  http://127.0.0.1:8091
 * </pre>
 *
 * <p><b>缺地址返回 empty 而不是抛异常</b>：单体形态下 {@code GATEWAY} 本来就没有地址
 * （网关内嵌在本进程里），那不是错误。由调用方把「没配地址」与「连不上」分开处理
 * —— 见 {@link InternalClient.Outcome}。
 *
 * <p><b>地址走内网、不经 nginx</b>：nginx 只做外部入口（域名、TLS、三端路径）。
 * 内部调用绕到 nginx 再回来，多一跳、多一份配置，而且加一个服务要 reload 全站。
 */
@Component
@ConfigurationProperties(prefix = "sharehub.services")
public class ServiceLocator {

    /** 服务名 → 基址（不含路径，末尾不带 `/`）。 */
    private Map<String, String> targets = new LinkedHashMap<>();

    public Map<String, String> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets == null ? new LinkedHashMap<>() : targets;
    }

    /** 基址；未配置返回 empty（不是异常 —— 单体形态下某些服务本就没有地址）。 */
    public Optional<String> baseUrlOf(String service) {
        String v = targets.get(service);
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        String trimmed = v.trim();
        return Optional.of(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
    }
}
