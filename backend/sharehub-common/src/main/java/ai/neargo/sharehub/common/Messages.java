package ai.neargo.powerbank.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * i18n 消息解析：按当前请求 Locale（{@code Accept-Language}）解析 key → 本地化文案。
 *
 * <p>约定：业务异常 {@code ServerException} 的 message 传 <b>消息 key</b>（如 {@code error.order.not_returnable}），
 * 在边界（{@link GlobalExceptionHandler}）经本类解析。非 key 的成品串原样返回（{@code useCodeAsDefaultMessage=true}）。
 */
@Component
public class Messages {

    private static MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        Messages.messageSource = messageSource;
    }

    /** 解析 key（含参数）→ 当前 Locale 文案；未初始化/异常时回退 key 本身。 */
    public static String msg(String key, Object... args) {
        if (key == null) {
            return null;
        }
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
    }
}
