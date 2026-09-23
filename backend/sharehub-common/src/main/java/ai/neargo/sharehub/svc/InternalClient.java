package ai.neargo.sharehub.svc;

import ai.neargo.sharehub.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 进程之间调用的那一半 —— <b>只管「怎么调」，不管「调哪里」</b>（后者见 {@link ServiceLocator}）。
 *
 * <p>移植自 ai-shop 的 {@code shop-base/svc/InternalClient}，配置前缀改为 {@code sharehub.services.*}。
 * 按 ADR-024 它属于将来下沉到框架的中性件，已登记 {@code known-pending-shared.txt}。
 *
 * <h2>四条规矩</h2>
 * <ol>
 *   <li><b>不经 nginx</b>：内部调用走内网地址。nginx 只做外部入口；让内部调用绕到 nginx
 *       再回来，多一跳、多一份配置，而且加一个服务要 reload 全站；</li>
 *   <li><b>共享密钥，不是用户令牌</b>：这条链路不认任何用户身份 ——
 *       内部调用代表的是「服务 A 要做这件事」，不是「某个用户要做这件事」；</li>
 *   <li><b>不记 body</b>：内部调用的参数可能带业务标识，调用链路的日志不该成为额外的数据出口；</li>
 *   <li><b>HTTP/1.1</b>：JDK 的 HttpClient 在 HTTP/2 下发大 body 会挂（ai-shop 的 job-worker 踩过）。</li>
 * </ol>
 *
 * <h2>三种失败必须分开</h2>
 * <b>没配地址</b>（改配置，不会自己好）、<b>连不上 / 超时</b>（等对方起来）、
 * <b>对方返回错误</b>（看对方日志）—— 混成一种的话，运维会守着一个永远不来的恢复。
 */
@Component
public class InternalClient {

    private static final Logger log = LoggerFactory.getLogger(InternalClient.class);

    /** 服务凭证头。与 {@code InternalTokenFilter} 必须一致。 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 调用结果的四种形态。 */
    public enum Outcome {
        /** 2xx。 */
        OK,
        /** 没配地址或没配密钥 —— 改配置才会好。 */
        NOT_CONFIGURED,
        /** 连不上或超时 —— 对方起来就好。 */
        UNREACHABLE,
        /** 对方明确返回了非 2xx —— 去看对方日志。 */
        REMOTE_ERROR
    }

    /**
     * @param outcome 四种形态之一
     * @param status  HTTP 状态；非 REMOTE_ERROR / OK 时为 0
     * @param body    响应体；失败时可能为 null
     * @param message 人话说明，直接可进日志与告警
     */
    public record Result(Outcome outcome, int status, String body, String message) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ServiceLocator locator;

    /**
     * 共享密钥。<b>没配就一律失败</b>，不是「没配就不校验」——
     * 后者的表现是内部口对任何人开放，而且没有任何症状。
     */
    private final String token;

    public InternalClient(ServiceLocator locator,
                          @Value("${sharehub.services.internal-token:}") String token) {
        this.locator = locator;
        this.token = token == null ? "" : token.trim();
    }

    public Result get(String service, String path, int timeoutSec) {
        return send(service, path, timeoutSec, null);
    }

    /**
     * @param service    服务名（见 {@link ServiceName}）
     * @param path       以 {@code /} 开头，如 {@code /internal/events/device}
     * @param jsonBody   请求体
     * @param timeoutSec 读超时（秒）
     */
    public Result post(String service, String path, String jsonBody, int timeoutSec) {
        return send(service, path, timeoutSec, jsonBody);
    }

    private Result send(String service, String path, int timeoutSec, String jsonBody) {
        Optional<String> base = locator.baseUrlOf(service);
        if (base.isEmpty()) {
            // **配置缺失单独一种**：它与「连不上」的区别是，这个不会自己好。
            return new Result(Outcome.NOT_CONFIGURED, 0, null,
                    "没有配置服务 " + service + " 的地址（sharehub.services.targets." + service + "）");
        }
        if (token.isBlank()) {
            return new Result(Outcome.NOT_CONFIGURED, 0, null,
                    "sharehub.services.internal-token 没配 —— 内部调用一律拒绝");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base.get() + path))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, token)
                // 带上当前链路：没有它，两个进程的日志只能靠时间戳猜
                .header(TraceContext.HEADER, TraceContext.currentOrNew())
                .timeout(Duration.ofSeconds(timeoutSec));
        HttpRequest req = (jsonBody == null
                ? builder.GET()
                : builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody))).build();

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return new Result(Outcome.OK, res.statusCode(), res.body(), null);
            }
            return new Result(Outcome.REMOTE_ERROR, res.statusCode(), res.body(),
                    service + " 返回 " + res.statusCode());
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(Outcome.UNREACHABLE, 0, null,
                    "调用 " + service + " 超时 " + timeoutSec + "s");
        } catch (Exception e) {
            // 不记 body，也不记异常堆栈里可能带的 URL 参数
            log.warn("内部调用失败 service={} path={} 异常={}", service, path, e.getClass().getSimpleName());
            return new Result(Outcome.UNREACHABLE, 0, null,
                    "调用 " + service + " 失败：" + e.getClass().getSimpleName());
        }
    }
}
