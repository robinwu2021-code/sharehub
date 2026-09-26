package ai.neargo.sharehub.auth;

import ai.neargo.sharehub.identity.IdentifierNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IdentifierNormalizer} 的契约测试。这个类此前没有用例 ——
 * 而它的输出决定了所有 {@code *_hash} 列的值，改一个字符就是全表失效。
 *
 * <p>最重要的一条是 {@link #phoneIsNotIdempotent()}：**它不是幂等的**，而它的调用方
 * 又互相传递「已归一的值」（{@code OtpGate} / {@code IdentifierHasher} 的入参契约都是这个）。
 * 任何一处「顺手再归一一次」都会静默算出另一个号。
 */
class IdentifierNormalizerTest {

    /** UAE，与 application.yml 的 default-calling-code 默认值一致。 */
    private final IdentifierNormalizer n = new IdentifierNormalizer("971");

    @Test
    @DisplayName("同一个号的各种写法收敛到同一个 E.164 数字串")
    void writeVariantsConverge() {
        assertThat(n.phone("+971 50 123 4567")).isEqualTo("971501234567");
        assertThat(n.phone("00971-50-123-4567")).isEqualTo("971501234567");
        assertThat(n.phone("0501234567")).isEqualTo("971501234567");   // 本地号：补区号、去前导 0
        assertThat(n.phone(" 501234567 ")).isEqualTo("971501234567");
    }

    /**
     * ⚠️ **归一不是幂等的，而且二次归一不会报错。**
     *
     * <p>{@code phone()} 的输出不带 {@code +}（{@code 971501234567}），再喂回去就被当成本地号
     * 又补一次区号 → {@code 971971501234567}，15 位，正好还在 E.164 上限内，
     * <b>校验放过、静默算出另一个人的 hash</b>。
     *
     * <p>所以「已归一的值」在链路上只能原样传递：{@code OtpService#issue}、
     * {@code OtpGate}、{@code IdentifierHasher#hash} 的入参契约都是「必须已归一」，
     * 它们都<b>不</b>自己再归一一次。这条用例的作用是让这个陷阱在代码里有个落点 ——
     * 否则它只存在于几段 javadoc 里，而「防御性地再归一一次」看起来完全无害。
     */
    @Test
    @DisplayName("⚠️ 二次归一会静默算出另一个号（所以链路上只能原样传递）")
    void phoneIsNotIdempotent() {
        String once = n.phone("+971501234567");
        assertThat(once).isEqualTo("971501234567");

        String twice = n.phone(once);
        assertThat(twice)
                .as("二次归一没有报错，只是悄悄变成了另一个号 —— 这正是它危险的地方")
                .isEqualTo("971971501234567")
                .isNotEqualTo(once);
    }

    @Test
    @DisplayName("邮箱用 Locale.ROOT 转小写（土耳其语环境下 I 不能变成无点 ı）")
    void emailLowercasesWithRootLocale() {
        assertThat(n.email("  Robin.WU@Example.COM ")).isEqualTo("robin.wu@example.com");
        assertThat(n.email("ROBIN@I.COM")).isEqualTo("robin@i.com");
    }

    @Test
    @DisplayName("默认区号没配 → 构造期就失败（不给默认值：静默算出多个 hash 比起不来糟得多）")
    void missingCallingCodeFailsFast() {
        assertThatThrownBy(() -> new IdentifierNormalizer(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-calling-code");
    }

    @Test
    @DisplayName("长度越界照抛，且异常消息里的号码是脱敏的")
    void rejectsOutOfRangeLengthWithoutLeakingTheNumber() {
        assertThatThrownBy(() -> n.phone("+9715012345678901234"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("*"));
    }
}
