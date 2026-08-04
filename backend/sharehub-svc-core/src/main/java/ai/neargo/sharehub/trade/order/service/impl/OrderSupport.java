package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Function;

/**
 * trade/order 子域内部工具（包内可见）。
 *
 * <p>本子域全是**有业务规则的聚合根**，按 [骨架规约 §3] 不走 {@code AbstractCrudService}，
 * 于是取号/打点/取当前登录人这三件小事没有基类兜底 —— 集中在这里，免得五个 impl 各抄一遍。
 */
final class OrderSupport {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    static final String TENANT_MAIN = "MAIN";

    private OrderSupport() {
    }

    /**
     * 统一时间戳：ISO-8601 本地日期时间（UTC 时钟，**无 {@code Z} 后缀**）。
     *
     * <p>原实现是 {@code Instant.now().toString()}，产出 {@code 2026-07-30T05:56:13.863054Z}。
     * 本子域的时间列（{@code ord_refund.applied_at/audited_at}、{@code ord_complaint.handled_at}
     * 等，见 V7）全是 {@code DATETIME(3)}，MariaDB 不接受带时区标记的字面量，
     * 于是每一次写入都是 {@code Data truncation: Incorrect datetime value} → 500。
     * 也就是说退款申请/投诉处理/异常处理/押金这几条写路径**从来没有成功过**
     * （本轮给退款加幂等测试时撞出来的）。
     *
     * <p>只去掉 DB 不认的 {@code Z}，时钟仍取 UTC —— 保持原注释声明的 UTC 语义不变，
     * 不顺手改成本地时区（那会让已有的展示值凭空偏移时区差）。
     * {@code wo} 域用的 {@code LocalDateTime.now().toString()} 是同一种格式，已验证可写入 DATETIME(3)。
     */
    static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).toString();
    }

    /** 当前登录人展示名（用于快照名列）。未认证时给 {@code system}，供内部作业调用。 */
    static String currentName() {
        return SecurityUtils.currentUser().map(LoginUser::username).orElse("system");
    }

    /** 当前登录人业务号（运营端 = employee_no）。 */
    static String currentNo() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("system");
    }

    /**
     * 取号：扫描同前缀最大号 + 1（[db-design §1.4.1]）。
     * <b>禁止</b>「前缀 + 数组长度」——该写法已在前端 mock 撞过两次主键。
     *
     * <p>并发下仍可能撞号，靠业务键 UNIQUE 兜底；调用方需处理 {@code DuplicateKeyException} 重试。
     *
     * @param column 业务键的**下划线列名**，如 {@code "refund_no"}
     * @param keyOf  从实体读业务键
     */
    static <E> String nextNo(BaseMapper<E> mapper, String column, String prefix, int width, Function<E, String> keyOf) {
        E top = mapper.selectOne(new QueryWrapper<E>()
                .likeRight(column, prefix)
                .orderByDesc(column)
                .last("limit 1"));

        long n = 0L;
        if (top != null) {
            String k = keyOf.apply(top);
            if (k != null && k.length() > prefix.length()) {
                String digits = k.substring(prefix.length()).replaceAll("\\D", "");
                if (!digits.isEmpty()) {
                    try {
                        n = Long.parseLong(digits);
                    } catch (NumberFormatException ignore) {
                        // 历史脏号（非纯数字后缀）不参与取号，从 0 重新计数由 UNIQUE 兜底
                    }
                }
            }
        }
        return prefix + String.format("%0" + width + "d", n + 1);
    }

    /** 分页参数归一：页码兜底 1，页长兜底 10、上限 200（与基类同口径）。 */
    static int page(Integer p) {
        return (p == null || p < 1) ? 1 : p;
    }

    static int size(Integer s) {
        return (s == null || s < 1) ? 10 : Math.min(s, 200);
    }

    static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}
