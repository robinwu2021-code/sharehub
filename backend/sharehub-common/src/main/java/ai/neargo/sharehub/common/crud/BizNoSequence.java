package ai.neargo.sharehub.common.crud;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * 顺序业务号取号：{@code 前缀 + 定宽数字}（{@code VEN305} / {@code AG009}）。
 *
 * <h2>为什么不是 ULID</h2>
 * 这个仓库里有两套取号并存：走 {@link AbstractCrudService} 的实体（员工、部门…）是顺序号，
 * 而一批手写 service 用 {@code IdGenerator.next(prefix)} —— 出来的是
 * {@code VENMUI9X6VY1M3XM5DQ} 这种 19 位 ULID。
 * 场地方编号、代理编号这类是**人要读、要念、要在合同和对账单上核对**的东西，
 * 两种形态并存没有理由，而且与各自的种子数据（{@code VEN300}/{@code AG001}）对不上。
 *
 * <p>本类把顺序取号从 {@link AbstractCrudService} 里摘出来，给**不继承它**的手写 service 用
 * —— 2026-09-26 场地方与代理商先后要它，再复制一遍就是第三份了。
 *
 * <h2>取最大值按数字序，不能用字典序</h2>
 * 位宽不一致时字典序会取错：实测提现单里同时有 {@code WD9002}(4 位) 与 {@code WD009003}(6 位)，
 * 字典序下 {@code 'WD9002' > 'WD009003'}，取到 9002 → 下一个算成 WD009003 → 撞已存在行。
 *
 * <h2>已知取舍：并发下会撞唯一键</h2>
 * 两个人同一瞬间建档，{@code MAX+1} 会算出同一个号，其中一个撞业务键唯一索引失败。
 * 场地方 / 代理商一天建几个，这个代价可以接受；要绝对安全得上号段表，不值得。
 * {@link AbstractCrudService#nextNo} 走的也是这条路，行为一致。
 */
public final class BizNoSequence {

    private BizNoSequence() {
    }

    /**
     * @param mapper    该实体的 mapper
     * @param column    业务键列名（下划线，如 {@code venue_no}）
     * @param prefix    业务键前缀（如 {@code VEN}）
     * @param width     数字部分补零宽度；超出自然进位，不截断
     */
    public static <E> String next(BaseMapper<E> mapper, String column, String prefix, int width) {
        List<Object> top = mapper.selectObjs(new QueryWrapper<E>()
                .select("MAX(CAST(SUBSTRING(" + column + ", " + (prefix.length() + 1) + ") AS UNSIGNED)) AS mx")
                .likeRight(column, prefix));
        long n = 0L;
        if (top != null && !top.isEmpty() && top.get(0) != null) {
            try {
                n = Long.parseLong(String.valueOf(top.get(0)));
            } catch (NumberFormatException ignore) {
                // 历史上有过 ULID 形态的号，取不出数字时从 0 起，由业务键唯一索引兜底
            }
        }
        return prefix + String.format("%0" + width + "d", n + 1);
    }
}
