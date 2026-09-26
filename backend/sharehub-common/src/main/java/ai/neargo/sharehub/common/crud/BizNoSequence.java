package ai.neargo.sharehub.common.crud;

import org.springframework.jdbc.core.JdbcTemplate;

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
 * <h2>⚠️ 必须绕开逻辑删除过滤，否则必撞唯一键</h2>
 * 这是 2026-09-26 在生产上实测炸出来的：审核通过建代理商时
 * {@code Duplicate entry 'AG010' for key 'uk_agent_no'}。
 *
 * <p>原因是两件事撞在一起：
 * <ul>
 *   <li>业务键的唯一索引（{@code uk_agent_no} / {@code uk_venue_no}）**不含 deleted 列**
 *       —— 这是对的：软删/归档掉的编号被合同、结算、划拨记录引用着，不能被重新发出去；</li>
 *   <li>而用 MyBatis-Plus 的 {@code QueryWrapper} 取 MAX 时，{@code @TableLogic}
 *       会自动补上 {@code deleted = 0} —— **取号看不见那些行**。</li>
 * </ul>
 * 于是只要有一个代理被归档或软删，它的号就变成一个洞，下一次取号必然走进去撞唯一键。
 * 症状是审核通过报 500，而看列表完全正常（那一行被逻辑删除藏起来了）。
 *
 * <p>所以这里**走原生 SQL**，不经 mapper：要的就是「连软删的一起算」这个语义，
 * 而它必须是显式的、不可能被某个拦截器悄悄改掉。
 *
 * <h2>取最大值按数字序，不能用字典序</h2>
 * 位宽不一致时字典序会取错：实测提现单里同时有 {@code WD9002}(4 位) 与 {@code WD009003}(6 位)，
 * 字典序下 {@code 'WD9002' > 'WD009003'}，取到 9002 → 下一个算成 WD009003 → 撞已存在行。
 *
 * <h2>已知取舍：并发下会撞唯一键</h2>
 * 两个人同一瞬间建档，{@code MAX+1} 会算出同一个号，其中一个撞业务键唯一索引失败。
 * 场地方 / 代理商一天建几个，这个代价可以接受；要绝对安全得上号段表，不值得。
 * {@link AbstractCrudService#nextNo} 直接委托到这里，两条路行为一致 ——
 * 它此前有同样的逻辑删除的洞（而基类的 remove() 正是软删），2026-09-26 一并收掉。
 */
public final class BizNoSequence {

    private BizNoSequence() {
    }

    /**
     * @param jdbc   原生 JdbcTemplate —— **刻意不用 mapper**，见类注释
     * @param table  表名（如 {@code agt_agent}）
     * @param column 业务键列名（下划线，如 {@code agent_no}）
     * @param prefix 业务键前缀（如 {@code AG}）
     * @param width  数字部分补零宽度；超出自然进位，不截断
     */
    public static String next(JdbcTemplate jdbc, String table, String column, String prefix, int width) {
        // 没有 where deleted=0：软删的号仍占着唯一索引，跳过它们就是在制造撞号
        Long max = jdbc.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(" + column + ", " + (prefix.length() + 1) + ") AS UNSIGNED))"
                        + " FROM " + table + " WHERE " + column + " LIKE ?",
                Long.class, prefix + "%");
        long n = max == null ? 0L : max;
        return prefix + String.format("%0" + width + "d", n + 1);
    }
}
