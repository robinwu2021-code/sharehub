package ai.neargo.sharehub.trade.order.service.impl;

import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

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
     */
    static <E> String nextNo(BaseMapper<E> mapper, String column, String prefix, int width) {
        /*
         * 数字序取最大，**不是字符串序**。
         *
         * 原先写的是 orderByDesc(column) 取第一行再从号里抠数字 —— 那是**字符串序**，
         * 只要表里混进一个非等宽/非纯数字的号，它就永远排在最前面，于是每次都算出同一个号：
         * 第二次建单直接撞唯一键 500。2026-09-28 在 PowerbankServiceImpl 上实测到了这个后果
         * （库里存量号是 PBDFECE7C9E0F 这种十六进制形，连续建第二只充电宝必定 500）。
         * 本方法服务的三张表（exception/complaint/refund）目前号还是齐整的，
         * 所以这里改的是**同一个形状的潜伏版本**，不是已经爆掉的缺陷。
         *
         * CAST(... AS UNSIGNED) 让非纯数字后缀算作 0，自然不参与取号 —— 不用再手写解析。
         */
        List<Object> top = mapper.selectObjs(new QueryWrapper<E>()
                .select("MAX(CAST(SUBSTRING(" + column + ", " + (prefix.length() + 1)
                        + ") AS UNSIGNED)) AS mx")
                .likeRight(column, prefix));
        long n = 0L;
        if (top != null && !top.isEmpty() && top.get(0) != null) {
            try {
                n = Long.parseLong(String.valueOf(top.get(0)));
            } catch (NumberFormatException ignore) {
                // 全是脏号时从 0 起，由业务键 UNIQUE 兜底
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
