package ai.neargo.sharehub.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 列的读写工具（`sys_biz_rule.rule` / `openapi_app.scopes` / `tenant.quota` /
 * `tenant_config.config_value` 等以 String 承载的 JSON 列统一走这里）。
 *
 * <p><b>为什么是静态工具而不是注入 {@code ObjectMapper} bean</b>：
 * Spring Boot 4 起 Jackson 自动配置产出的是 <b>Jackson 3</b>（{@code tools.jackson.databind.ObjectMapper}），
 * 容器里<b>没有</b> Jackson 2 的 {@code com.fasterxml.jackson.databind.ObjectMapper} bean。
 * 服务里直接注入后者会在启动期报 {@code NoSuchBeanDefinitionException}（本类即为修此问题而建）。
 * 同工程的 {@code TokenStoreConfig} 早已用「自建实例、不依赖 web 上下文 bean」规避，本类把该做法收敛为一处。
 *
 * <p>业务域<b>不要</b>再注入 {@code ObjectMapper}；HTTP 出入参的序列化仍由 Spring MVC 自己的
 * 消息转换器负责，与本类无关，两者互不影响。
 *
 * <p>宽容读取：忽略未知字段 —— JSON 列是配置载体，新增字段不应让旧行读不出来。
 */
public final class Json {

    private static final ObjectMapper OM = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    /** 序列化；{@code null} 原样返回 {@code null}（不写 "null" 字符串进库）。 */
    public static String write(Object value) {
        if (value == null) return null;
        try {
            return OM.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * 反序列化；空串/{@code null}/解析失败一律返回 {@code fallback}。
     *
     * <p>解析失败**不抛异常**是有意的：JSON 配置列可能被人工改坏，
     * 让一行坏配置把整个页面打成 500 不如回落默认值 —— 调用方拿到的仍是可用配置。
     */
    public static <T> T read(String raw, Class<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return OM.readValue(raw, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 无默认值版本；解析失败抛异常。用于「读不出来就该炸」的场景。 */
    public static <T> T read(String raw, Class<T> type) {
        try {
            return OM.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + type.getSimpleName(), e);
        }
    }
}
