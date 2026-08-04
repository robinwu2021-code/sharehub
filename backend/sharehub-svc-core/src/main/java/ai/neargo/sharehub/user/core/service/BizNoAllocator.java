package ai.neargo.sharehub.user.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.function.Function;

/**
 * 业务键取号（[db-design §1.4.1]）：<b>扫描同前缀已有号取 max+1</b>。
 *
 * <p>{@code AbstractCrudService#nextNo} 只服务于继承它的字典类；user 域有一半的表
 * （黑名单/白名单/消息/发票/充值订单/会员…）是手写聚合根、或实体压根不继承 {@code BaseEntity}
 * （append 表与缺 version/deleted 的历史表），够不着那个方法 —— 故把同一套规则抽成静态工具，
 * 而不是在每个 impl 里抄一遍。
 *
 * <p><b>禁止「前缀 + 数组长度/count」</b>：删过行之后必撞主键（前端 mock 已踩过两次）。
 * 并发下仍可能撞号，靠业务键 UNIQUE 兜底，调用方处理 {@code DuplicateKeyException} 重试。
 */
public final class BizNoAllocator {

    private BizNoAllocator() {
    }

    /**
     * @param column 业务键的**下划线列名**，如 {@code "blacklist_no"}
     * @param keyOf  从实体读业务键
     * @param width  数字部分补零宽度
     */
    public static <E> String next(BaseMapper<E> mapper, String column, String prefix,
                                  Function<E, String> keyOf, int width) {
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

    /** 默认 4 位补零，与 {@code AbstractCrudService#keyWidth()} 一致。 */
    public static <E> String next(BaseMapper<E> mapper, String column, String prefix,
                                  Function<E, String> keyOf) {
        return next(mapper, column, prefix, keyOf, 4);
    }
}
