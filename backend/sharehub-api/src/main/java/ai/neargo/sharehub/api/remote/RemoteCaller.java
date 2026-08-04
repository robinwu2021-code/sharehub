package ai.neargo.sharehub.api.remote;

import ai.neargo.sharehub.auth.CallContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Function;

/**
 * 跨服务调用的统一出口：拼地址 + 拆 {@code Result<T>} 信封 + 统一失败语义。
 *
 * <p><b>为什么要拆信封</b>：`ApiResponseWrapper` 把所有响应包成 `{code,message,data}`，
 * 远程实现若直接反序列化成 Port 的返回类型，拿到的会是 null。
 * 这类错误在单体下永远不会出现，**只在拆分后才暴露**。
 */
public class RemoteCaller {

    private final RestClient client;
    private final RemoteProperties props;

    public RemoteCaller(RestClient client, RemoteProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 取某服务的基地址。
     *
     * <p><b>缺地址立即抛异常而不是返回 null</b>：微服务形态下配置漏了，
     * 应该在第一次调用时就炸得清清楚楚，而不是让 NPE 在下游某处冒出来。
     */
    public String baseUrl(String service) {
        String url = props.getRemotes().get(service);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "未配置服务地址：sharehub.deploy.remotes." + service
                            + " —— 微服务形态下必须显式配置。当前 mode=" + props.getMode());
        }
        return url;
    }

    /**
     * GET，出参是 {@code Result<T>} 的 data 部分。
     *
     * <p><b>透传调用方 token</b>：对端用自己的 TokenStore 反查，得到同一个身份与数据范围。
     * 权限判定始终发生在持有 TokenStore 的那一侧 —— 与单体形态语义一致。
     */
    public <T> T get(String service, String path, Map<String, ?> query,
                     ParameterizedTypeReference<Envelope<T>> type) {
        String url = baseUrl(service) + path + queryString(query);
        Envelope<T> env = client.get().uri(url)
                .headers(h -> auth(h::set))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("跨服务调用失败 " + service + " " + path
                            + " → HTTP " + res.getStatusCode());
                })
                .body(type);
        return unwrap(env, service, path);
    }

    /** POST，出参同上。 */
    public <T> T post(String service, String path, Object body,
                      ParameterizedTypeReference<Envelope<T>> type) {
        String url = baseUrl(service) + path;
        Envelope<T> env = client.post().uri(url)
                .headers(h -> auth(h::set))
                .body(body == null ? Map.of() : body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("跨服务调用失败 " + service + " " + path
                            + " → HTTP " + res.getStatusCode());
                })
                .body(type);
        return unwrap(env, service, path);
    }

    private <T> T unwrap(Envelope<T> env, String service, String path) {
        if (env == null) {
            throw new IllegalStateException("跨服务调用无响应体：" + service + " " + path);
        }
        if (env.code() != 0) {
            throw new IllegalStateException("跨服务调用返回业务错误：" + service + " " + path
                    + " code=" + env.code() + " msg=" + env.message());
        }
        return env.data();
    }

    /**
     * 附加认证头。
     *
     * <p><b>无 token 时不加而不是报错</b>：后台任务（对账、投递器）本就没有调用方身份，
     * 它们调 `/internal` 时应由部署层用服务账号凭证兜底 —— 在这里硬报错会让定时任务无法运行。
     * 但**依赖会话的 Port（如 existsInScope）在无 token 时结果不正确**，
     * 那类调用不应出现在后台任务里。
     */
    private static void auth(java.util.function.BiConsumer<String, String> setter) {
        String t = CallContext.token();
        if (t != null && !t.isBlank()) {
            setter.accept("Authorization", "Bearer " + t);
        }
    }

    private static String queryString(Map<String, ?> q) {
        if (q == null || q.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        q.forEach((k, v) -> {
            if (v != null) sb.append(k).append('=').append(v).append('&');
        });
        return sb.substring(0, sb.length() - 1);
    }

    /** {@code Result<T>} 的最小镜像 —— api 模块不依赖 neargo-common-core 的具体实现。 */
    public record Envelope<T>(int code, String message, T data) {
    }

    /** 便于测试注入的工厂。 */
    public static RemoteCaller of(Function<Void, RestClient> f, RemoteProperties p) {
        return new RemoteCaller(f.apply(null), p);
    }
}
