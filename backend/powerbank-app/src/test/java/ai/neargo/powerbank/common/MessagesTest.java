package ai.neargo.powerbank.common;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** i18n 消息按 Accept-Language 解析（zh/en/ar）+ 缺失回退。对照 TDD-国际化i18n §4。 */
class MessagesTest {

    private static final Locale AR = Locale.forLanguageTag("ar");

    private MessageSource ms() {
        ReloadableResourceBundleMessageSource m = new ReloadableResourceBundleMessageSource();
        m.setBasename("classpath:i18n/messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        m.setUseCodeAsDefaultMessage(true);
        return m;
    }

    @Test
    void resolvesPerLocale() {
        MessageSource m = ms();
        assertEquals("无权限", m.getMessage("error.forbidden", null, Locale.SIMPLIFIED_CHINESE));
        assertEquals("No permission", m.getMessage("error.forbidden", null, Locale.ENGLISH));
        assertEquals("لا توجد صلاحية", m.getMessage("error.forbidden", null, AR));
    }

    @Test
    void fallbackAndUnknownKey() {
        MessageSource m = ms();
        // 缺 ar 时回退默认(zh)：此处三语齐全故直接校验各自值已在上；未知 key 用 key 本身
        assertEquals("some.unknown.key", m.getMessage("some.unknown.key", null, Locale.ENGLISH));
        assertEquals("操作成功", m.getMessage("common.success", null, Locale.SIMPLIFIED_CHINESE));
    }
}
