package ai.neargo.sharehub.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录标识规范化与哈希。
 *
 * <p>这组测试盯的是<b>一类不会报错的故障</b>：规范化不一致 → 注册写进去的 hash 与
 * 登录算出来的对不上 → <b>「注册能成、登录查不到」</b>。它只在带空格 / 连字符 /
 * 国际区号 / 大写字母的输入上出现，抽样测很容易全部漏掉。
 */
class IdentifierTest {

    private static final String PEPPER = "test-pepper-at-least-32-characters-long!";
    private final IdentifierNormalizer n = new IdentifierNormalizer("971");
    private final IdentifierHasher h = new IdentifierHasher(PEPPER, 1);

    @Test
    @DisplayName("★★★ 同一个号的各种写法必须算出同一个 hash —— 否则注册能成、登录查不到")
    void sameNumberDifferentSpellings() {
        String canonical = n.phone("+971501234567");
        assertAll(
                () -> assertEquals(canonical, n.phone("+971 50 123 4567"), "带空格"),
                () -> assertEquals(canonical, n.phone("+971-50-123-4567"), "带连字符"),
                () -> assertEquals(canonical, n.phone("00971501234567"), "00 前缀"),
                () -> assertEquals(canonical, n.phone("  +971501234567  "), "前后空白"),
                () -> assertEquals(canonical, n.phone("0501234567"), "本地号带前导 0"),
                () -> assertEquals(canonical, n.phone("501234567"), "本地号无前导 0"));
        assertEquals(h.hash(canonical), h.hash(n.phone("+971 50-123 4567")));
    }

    @Test
    @DisplayName("★★★ 掩码会把不同的号碰撞成同一个值 —— 这正是它不能做登录键的原因")
    void masksCollide() {
        String a = n.phone("+8613800138000");
        String b = n.phone("+8613811138000");
        assertNotEquals(a, b, "两个号本身不同");
        assertNotEquals(h.hash(a), h.hash(b), "hash 必须不同");
        assertEquals(IdentifierNormalizer.mask(a), IdentifierNormalizer.mask(b),
                "而掩码相同 —— 拿掩码做唯一键会把两个人判成一个");
    }

    @Test
    @DisplayName("★★ 邮箱大小写与空白无关；用 ROOT locale，不受土耳其语 I 影响")
    void emailNormalization() {
        String c = IdentifierNormalizer.email("Robin@Example.COM");
        assertEquals("robin@example.com", c);
        assertEquals(c, IdentifierNormalizer.email("  ROBIN@EXAMPLE.COM  "));
        // 土耳其语 locale 下 "I".toLowerCase() 会得到 'ı'（无点小写 i）。
        // 用 Locale.ROOT 才能保证同一个邮箱在不同机器上算出同一个 hash。
        assertEquals("iban@example.com", IdentifierNormalizer.email("IBAN@example.com"));
    }

    @Test
    @DisplayName("★★ identifier 单字段：含 @ 走邮箱，否则走手机号")
    void identifierRouting() {
        assertTrue(IdentifierNormalizer.looksLikeEmail("a@b.com"));
        assertTrue(!IdentifierNormalizer.looksLikeEmail("+971501234567"));
    }

    @Test
    @DisplayName("★★★ pepper 缺失 / 过短一律启动失败 —— 裸哈希对手机号等于明文存储")
    void pepperIsMandatory() {
        assertThrows(IllegalStateException.class, () -> new IdentifierHasher(null, 1));
        assertThrows(IllegalStateException.class, () -> new IdentifierHasher("  ", 1));
        assertThrows(IllegalStateException.class, () -> new IdentifierHasher("too-short", 1));
    }

    @Test
    @DisplayName("★★ 换 pepper 会让全部 hash 失效 —— 轮换必须走 *_enc 全表重算")
    void pepperRotationInvalidatesHashes() {
        String phone = n.phone("+971501234567");
        IdentifierHasher v2 = new IdentifierHasher("another-pepper-also-32-chars-long-xx", 2);
        assertNotEquals(h.hash(phone), v2.hash(phone));
        assertEquals(2, v2.version());
    }

    @Test
    @DisplayName("★★ hash 输出恒为 64 位十六进制 —— 与 VARCHAR(64) 列对齐")
    void hashShape() {
        String hash = h.hash(n.phone("+971501234567"));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "必须是小写十六进制");
    }

    @Test
    @DisplayName("默认区号缺失时启动失败 —— 没有它，同一个本地号会产生多个 hash")
    void callingCodeIsMandatory() {
        assertThrows(IllegalStateException.class, () -> new IdentifierNormalizer(""));
        assertThrows(IllegalStateException.class, () -> new IdentifierNormalizer(null));
    }

    @Test
    @DisplayName("非法输入拒绝")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> n.phone(""));
        assertThrows(IllegalArgumentException.class, () -> n.phone("abc"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierNormalizer.email("nope"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierNormalizer.email("a@b"));
    }
}
