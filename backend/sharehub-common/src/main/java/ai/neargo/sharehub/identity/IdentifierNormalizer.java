package ai.neargo.sharehub.identity;

/**
 * 登录标识（手机号 / 邮箱）的规范化。
 *
 * <p><b>这个类的输出决定了 {@code phone_hash} / {@code email_hash} 的值，因此它一旦上线就不能改。</b>
 * 改了等于全表 hash 失效：注册时写进去的和登录时算出来的对不上，表现是
 * <b>「注册能成、登录查不到」</b>，而且只在带空格 / 连字符 / 国际区号的输入上出现 —— 抽样测很容易漏。
 *
 * <p>注册与登录<b>必须调同一个方法</b>（ADR-030 §4.1）。各写一遍规范化是这条链上最隐蔽的坑。
 *
 * <p>规范化规则（确定、无配置依赖、无 locale 依赖）：
 * <ul>
 *   <li>手机号：{@code +} 或 {@code 00} 开头视为已带国际区号，去掉所有非数字；
 *       否则按 {@link #defaultCallingCode} 补区号。<b>默认区号与 pepper 同级——上线后不可改。</b></li>
 *   <li>邮箱：{@code trim} + 转小写。<b>用 {@link java.util.Locale#ROOT}</b>，
 *       否则土耳其语环境下 {@code I} 会转成 {@code ı}（无点小写 i），同一个邮箱在不同机器上算出两个 hash。</li>
 * </ul>
 */
public final class IdentifierNormalizer {

    private final String defaultCallingCode;

    public IdentifierNormalizer(String defaultCallingCode) {
        String code = defaultCallingCode == null ? "" : defaultCallingCode.replaceAll("\\D", "");
        if (code.isEmpty()) {
            throw new IllegalStateException(
                    "sharehub.security.default-calling-code 未配置：没有它就无法把本地号规范化成唯一形式，"
                            + "同一个号会因为填法不同产生多个 hash");
        }
        this.defaultCallingCode = code;
    }

    /** 输入看起来是邮箱吗 —— 登录时用它选择走哪个 hash（ADR-030 §4.1 的 identifier 单字段）。 */
    public static boolean looksLikeEmail(String raw) {
        return raw != null && raw.indexOf('@') >= 0;
    }

    /**
     * 手机号 → 纯数字的国际格式（不带 {@code +}）。
     *
     * <p>例：{@code "138 0013-8000"} → {@code "86138001380 00"} 之类的本地号会补默认区号；
     * {@code "+971 50 123 4567"} → {@code "971501234567"}。
     */
    public String phone(String raw) {
        if (raw == null) throw new IllegalArgumentException("手机号必填");
        String t = raw.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("手机号必填");

        boolean international = t.startsWith("+") || t.startsWith("00");
        String digits = t.replaceAll("\\D", "");
        if (t.startsWith("00")) digits = digits.substring(2);
        if (digits.isEmpty()) throw new IllegalArgumentException("手机号不含数字: " + raw);

        String full = international ? digits : defaultCallingCode + stripLeadingZero(digits);
        // 国际号码最长 15 位（E.164）；这里只做粗校验，真实校验归业务层
        if (full.length() < 7 || full.length() > 15) {
            throw new IllegalArgumentException("手机号长度不合法: " + mask(full));
        }
        return full;
    }

    /** 邮箱 → trim + ROOT 小写。 */
    public static String email(String raw) {
        if (raw == null) throw new IllegalArgumentException("邮箱必填");
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        int at = t.indexOf('@');
        if (at <= 0 || at == t.length() - 1 || t.indexOf('.', at) < 0) {
            throw new IllegalArgumentException("邮箱格式不合法");
        }
        return t;
    }

    /**
     * 本地号常见写法会带一个前导 0（如 {@code 050…}），补区号时要去掉。
     * 只去一个，且只在补区号这条路径上去 —— 国际号里的 0 是有意义的。
     */
    private static String stripLeadingZero(String digits) {
        return digits.length() > 1 && digits.charAt(0) == '0' ? digits.substring(1) : digits;
    }

    /** 掩码：保留前 3 后 4，中间打星。<b>仅供显示与日志，绝不可进唯一键或等值查询</b>（ADR-030 §2.3）。 */
    public static String mask(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.length() < 7) return "***";
        return normalizedPhone.substring(0, 3) + "****" + normalizedPhone.substring(normalizedPhone.length() - 4);
    }

    /** 邮箱掩码：首字符 + 星 + @域名。同样只供显示。 */
    public static String maskEmail(String normalizedEmail) {
        if (normalizedEmail == null) return "***";
        int at = normalizedEmail.indexOf('@');
        if (at <= 0) return "***";
        return normalizedEmail.charAt(0) + "***" + normalizedEmail.substring(at);
    }
}
