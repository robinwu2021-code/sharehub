package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentAssignment;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AgentPerformance;
import ai.neargo.sharehub.agent.ext.service.AgentPerformanceService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理绩效读模型 —— **当前为空实现**（返回空页），端点先通、口径后填。
 *
 * <p>两个方法都要跨域聚合 {@code ord_order}（trade 域）与 {@code dev_cabinet}（dev 域），
 * 这两个域的 mapper 不在本分片边界内；且绩效榜的 {@code rank} 必须在**同一条 SQL** 里算，
 * 分两次查再在 Java 里排会与分页打架（分页后排名只在当前页内成立，是经典错觉 bug）。
 *
 * <p>TODO(读模型): 落地方式建议自定义 Mapper XML，一条 GROUP BY + 窗口函数出结果。SQL 骨架：
 * <pre>
 *   SELECT a.agent_no, a.name AS agent_name,
 *          COALESCE(SUM(o.fee_amount), 0)                            AS gmv,
 *          RANK() OVER (ORDER BY COALESCE(SUM(o.fee_amount),0) DESC) AS `rank`
 *     FROM agt_agent a
 *     LEFT JOIN ord_order o
 *           ON o.agent_no = a.agent_no          -- ADR-012 归属冗余列，免子查询
 *          AND o.deleted = 0
 *          AND o.rent_start_at BETWEEN #{from} AND #{to}
 *    WHERE a.deleted = 0
 *    GROUP BY a.agent_no, a.name
 * </pre>
 * {@code cabinetCount} / {@code onlineRate} 再 join {@code dev_cabinet} 按 {@code agent_no} 聚合
 * （**不要读 {@code agt_agent.cabinet_count} 列** —— [db-design §1.4]「计数列不是列，是聚合」）。
 * {@code assignments()} 的 {@code region} 取 {@code agt_agent_region} 拼串
 * （见 {@link ai.neargo.sharehub.agent.ext.service.AgentRegionService#csvOf}）。
 */
@Service
public class AgentPerformanceServiceImpl implements AgentPerformanceService {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AgentPerformanceServiceImpl(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<AgentPerformance> page(Integer page, Integer size, String keyword, String from, String to) {
        return new PageResult<>(List.of(), 0L);
    }

    /**
     * 代理名下资产汇总（站点数 / 机柜数）。
     *
     * <p><b>此前是空实现</b>：永远返回空列表，而端点、权限、前端调用都齐备 ——
     * 于是「代理名下机柜 0 台」看起来像真的，实际是这个方法什么都没查。
     * 运营端为此改成从「可划拨资产池」按归属数，那是另一个口径（池子里只有能划拨的），
     * 数出来的与档案上该显示的不是一回事。
     *
     * <p>口径：{@code agent_no} 直接挂在站点 / 机柜上的才算；归档与软删的不算。
     * 站点的运维 / 拓展责任（{@code loc_site_agent}）不计入 —— 那是「谁在服务」，
     * 不是「归谁」，清退时收的也是这两类归属。
     */
    @Override
    public PageResult<AgentAssignment> assignments(Integer page, Integer size, String keyword) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        String where = "a.deleted = 0 AND a.archived_at IS NULL" + (like == null ? "" : " AND (a.agent_no LIKE ? OR a.name LIKE ?)");
        Object[] args = like == null ? new Object[0] : new Object[]{like, like};

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agt_agent a WHERE " + where, Long.class, args);
        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = s;
        pageArgs[args.length + 1] = (long) (p - 1) * s;
        List<AgentAssignment> rows = jdbc.query(
                "SELECT a.agent_no, a.name, a.region_scope,"
                        + " (SELECT COUNT(*) FROM dev_cabinet c WHERE c.agent_no = a.agent_no AND c.deleted = 0 AND c.archived_at IS NULL) cabs,"
                        + " (SELECT COUNT(*) FROM loc_site t WHERE t.agent_no = a.agent_no AND t.deleted = 0) sites"
                        + " FROM agt_agent a WHERE " + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AgentAssignment(rs.getString("agent_no"), rs.getString("name"),
                        rs.getString("region_scope"), rs.getInt("cabs"), rs.getInt("sites")),
                pageArgs);
        return new PageResult<>(rows, total == null ? 0L : total);
    }
}
