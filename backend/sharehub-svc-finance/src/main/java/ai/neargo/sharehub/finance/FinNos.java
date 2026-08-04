package ai.neargo.sharehub.finance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * 业务键取号（手写聚合根专用）。
 *
 * <p><b>为什么不复用 {@code AbstractCrudService#nextNo}</b>：那套挂在字典类 CRUD 基类上，
 * 而财务域全是手写聚合根 + 一张只增表（{@code acct_ledger} 连 {@code BaseEntity} 都不继承），
 * 继承不上。规则与它完全一致 —— [db-design §1.4.1]「扫描同前缀已有号取 max+1」，
 * <b>禁止「前缀 + 集合长度」</b>（该写法在前端 mock 已导致过两次主键撞号）。
 *
 * <p>并发下仍可能撞号，靠业务键 UNIQUE 兜底，调用方需处理 {@code DuplicateKeyException} 重试。
 */
public final class FinNos {

    private FinNos() {
    }

    /**
     * 扫描同前缀最大号 +1。
     *
     * @param keyColumn 业务键的**下划线列名**，如 {@code "settle_no"}；仅由本包内的常量传入，不接受外部输入
     * @param width     数字部分补零宽度
     */
    /** 按数字取最大值，不能用字典序。
     *
     * <p><b>为什么不能 ORDER BY 业务键 DESC</b>：位宽不一致时字典序会取错。
     * 实测：库里同时有 {@code WD9002}（4 位，历史/人工插入）与 {@code WD009003}（6 位，本方法生成），
     * 字典序下 {@code 'WD9002' > 'WD009003'}（第 3 位 '9' > '0'），于是取到 9002、
     * 下一个算成 {@code WD009003} —— 撞上已存在的行，报 Duplicate entry。
     * 改为把前缀后的部分 CAST 成无符号整数再取 MAX。 */
    public static <E> String nextNo(BaseMapper<E> mapper, String keyColumn, String prefix, int width) {
        // 数字序取最大：CAST 掉前缀后的部分，位宽不一致也正确（见方法注释）
        List<Object> top = mapper.selectObjs(new QueryWrapper<E>()
                .select("MAX(CAST(SUBSTRING(" + keyColumn + ", " + (prefix.length() + 1)
                        + ") AS UNSIGNED)) AS mx")
                .likeRight(keyColumn, prefix));

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
}
