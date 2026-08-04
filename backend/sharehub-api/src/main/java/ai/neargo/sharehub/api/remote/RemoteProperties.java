package ai.neargo.sharehub.api.remote;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各服务的远程地址（ADR-017 §5.2）。
 *
 * <pre>
 * sharehub:
 *   deploy:
 *     remotes:
 *       core:     http://svc-core:8080
 *       platform: http://svc-platform:8080
 * </pre>
 *
 * <p><b>只在微服务形态下被用到</b>：单体里本地实现优先，这些地址一次都不会被读。
 * 所以配置缺失不是错误 —— 但**微服务形态下缺地址必须 fail-fast**，
 * 否则会退化成运行到一半才发现调不通（见 {@link RemoteCaller#baseUrl}）。
 */
@ConfigurationProperties(prefix = "sharehub.deploy")
public class RemoteProperties {

    /** 形态自述：mono / micro。**仅用于日志与健康检查，不参与装配决策** —— 装配由 classpath 决定。 */
    private String mode = "mono";

    private Map<String, String> remotes = new LinkedHashMap<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Map<String, String> getRemotes() {
        return remotes;
    }

    public void setRemotes(Map<String, String> remotes) {
        this.remotes = remotes;
    }
}
