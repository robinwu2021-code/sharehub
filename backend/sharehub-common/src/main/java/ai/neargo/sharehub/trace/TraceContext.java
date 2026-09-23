package ai.neargo.sharehub.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 链路标识的进程内载体 —— 只做**透传与日志关联**，不接 OpenTelemetry。
 *
 * <p>格式沿用 W3C Trace Context 的 {@code traceparent}：
 * {@code 00-<32 位 traceId>-<16 位 spanId>-01}。用标准格式而不是自造一个 requestId，
 * 是因为将来真要接 OTel 时，入站的 header 能直接被它认出来、链路不断在我们这一跳。
 *
 * <p><b>为什么不引 OTel SDK</b>：现在只有两个进程，需要的是「日志能串起来」，
 * 而 OTel 还要 collector、导出器与一套部署组件。等进程多到看不清调用关系时再上，
 * 那时本类的 header 已经是对的，换实现不影响调用方。
 *
 * <p>{@code traceId} 同时写进 MDC（键 {@code traceId}），日志模板加 {@code %X{traceId\}} 即可输出。
 */
public final class TraceContext {

    /** W3C 标准头名。 */
    public static final String HEADER = "traceparent";

    /** MDC 键：日志模板用 {@code %X{traceId}}。 */
    public static final String MDC_KEY = "traceId";

    /** {@code 00-<32hex>-<16hex>-<2hex>}；只认 version 00（目前唯一定义的版本）。 */
    private static final Pattern VALID =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 采纳入站的 traceparent；格式不合法或缺失则新生成一个。
     *
     * <p><b>不合法就重新生成，而不是拒绝请求</b>：链路标识是可观测性设施，
     * 不该成为业务请求失败的原因。
     *
     * @return 实际生效的 traceparent
     */
    public static String adopt(String inbound) {
        String tp = (inbound != null && VALID.matcher(inbound).matches()) ? inbound : newTraceparent();
        CURRENT.set(tp);
        MDC.put(MDC_KEY, traceIdOf(tp));
        return tp;
    }

    /** 当前 traceparent；没有就地生成一个并记下（出站调用不该是无链路的）。 */
    public static String currentOrNew() {
        String tp = CURRENT.get();
        if (tp == null) {
            tp = newTraceparent();
            CURRENT.set(tp);
            MDC.put(MDC_KEY, traceIdOf(tp));
        }
        return tp;
    }

    /** 当前 traceId（32 位十六进制）；无链路时返回 null。 */
    public static String currentTraceId() {
        String tp = CURRENT.get();
        return tp == null ? null : traceIdOf(tp);
    }

    /** 请求结束必须清理：线程池复用会把上一个请求的链路带给下一个。 */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    static String traceIdOf(String traceparent) {
        return traceparent.substring(3, 35);
    }

    private static String newTraceparent() {
        return "00-" + hex(16) + "-" + hex(8) + "-01";
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
