package ai.neargo.opskit.commons.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;

/**
 * 编号 = 前缀 + 26 位 Crockford Base32（48 位毫秒时间 + 80 位随机，即 ULID 布局）。
 *
 * <p>进程内生成、不取号、不依赖序列表：归档一行不会在编号上挖洞，多实例也无需协调。
 * 毫秒级有序（同一毫秒内的先后不保证），足够列表按编号近似按时间排序。
 */
public final class BizNoGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int MAX_PREFIX = 12;

    private final Clock clock;
    private final SecureRandom random;

    public BizNoGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    BizNoGenerator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    /** 业务记录号，大写，如 {@code TK01J8Z3…}（总长 ≤ 40，与编号列宽一致）。 */
    public String next(String prefix) {
        requirePrefix(prefix);
        return prefix + ulid();
    }

    /** 对外资源 ID，小写并带下划线，如 {@code ws_01j8z3…}（总长 ≤ 32，与租户列宽一致）。 */
    public String nextPublicId(String prefix) {
        requirePrefix(prefix);
        String id = prefix.toLowerCase(Locale.ROOT) + "_" + ulid().toLowerCase(Locale.ROOT);
        if (id.length() > 32) {
            throw new IllegalArgumentException("public id prefix too long: " + prefix);
        }
        return id;
    }

    private static void requirePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.length() > MAX_PREFIX || !prefix.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("prefix must be 1.." + MAX_PREFIX + " letters: " + prefix);
        }
    }

    private String ulid() {
        long time = clock.millis();
        byte[] rnd = new byte[10];
        random.nextBytes(rnd);
        char[] out = new char[26];
        // 48 位时间 → 10 个字符
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 位随机 → 16 个字符
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (rnd[i] & 0xff);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (rnd[i] & 0xff);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
