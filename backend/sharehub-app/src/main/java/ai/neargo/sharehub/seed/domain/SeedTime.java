package ai.neargo.sharehub.seed.domain;

/**
 * 演示数据的时间格式转换。
 *
 * <h2>为什么需要它</h2>
 *
 * 全项目有 90 处「实体字段是 {@code String}、而列是 DATE/DATETIME」。运行时代码写入前
 * 都会先格式化（如 {@code RentOrderServiceImpl#nowUtc}，那里的注释写明
 * 「DATETIME(3) 不接受带 Z 的字面量」），唯独**种子直接把 ISO 串塞进去**：
 * {@code SeedData.iso()} 产出的是 {@code 2026-09-23T03:00:00Z}，
 * 于是干净库上灌种会报
 * 「Incorrect datetime value … Data truncation」，应用起不来。
 *
 * <p>本机库里之所以从没暴露：**没有人在干净库上灌过种**。
 *
 * <h2>为什么不改 SeedData.iso() 本身</h2>
 *
 * {@code SeedData} 的 DTO 形状仍被多个控制器引用（它们按 ISO 出参）。
 * 在那里改格式会让接口返回 {@code 2026-09-23 03:00:00} —— 前端 {@code new Date(...)}
 * 会把它当**本地时间**解析，整站时间平移几个小时，而且没有任何报错。
 * 所以转换只发生在**写库这一步**。
 */
final class SeedTime {

    private SeedTime() {
    }

    /** ISO 时刻 → DATETIME 列能吃的 {@code yyyy-MM-dd HH:mm:ss}。null 原样返回。 */
    static String dt(String iso) {
        if (iso == null || iso.isBlank()) return iso;
        String s = iso.trim();
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
        s = s.replace('T', ' ');
        // 去掉毫秒后的时区偏移（+04:00），DATETIME 列不认
        int plus = s.indexOf('+');
        if (plus > 10) s = s.substring(0, plus);
        return s;
    }

    /** ISO 时刻 → DATE 列能吃的 {@code yyyy-MM-dd}。 */
    static String date(String iso) {
        if (iso == null || iso.isBlank()) return iso;
        return iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }
}
