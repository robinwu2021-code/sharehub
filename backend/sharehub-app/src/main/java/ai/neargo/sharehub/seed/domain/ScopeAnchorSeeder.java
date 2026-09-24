package ai.neargo.sharehub.seed.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 灌种之后回填**数据范围锚点**（`loc_location.agent_no` → `dev_cabinet` → `ord_order` / `wo_order`）。
 *
 * <h2>为什么必须有这一步</h2>
 *
 * 这些冗余列由 V9 一次性回填 —— 那是针对**当时库里已有的数据**。
 * 而种子是在所有迁移**跑完之后**才插入的，于是新灌的数据一个锚点都没有：
 * {@code DataScopeHandler} 按 `agent_no` 过滤，代理商登录后**什么都看不到**，
 * 而且不报错，界面只是空的。
 *
 * <h2>2026-09-24：这里曾经是唯一的写入者</h2>
 * 在此之前，{@code ord_order} 的三列<b>根本没有写入路径</b> ——
 * {@code OrdOrder} 实体缺这三个字段，下单时写不进去；工单那边虽然写，
 * 但值取自请求体，而 ops-web 压根不传。于是真实流程产生的单据锚点一律为空，
 * 而本类 {@code @ConditionalOnProperty(sharehub.seed.enabled=true)} 在生产是关的 ——
 * <b>也就是说生产上根本没人填过它们</b>。
 *
 * <p>写入路径已经补上（{@code RentOrderServiceImpl.rent} 与
 * {@code WoOpsServiceImpl.create} 都从机柜反查），存量由 V62 回填。
 * 本类因此退回它本来的职责：<b>只管种子数据</b>。
 *
 * <p>本机库之所以一直正常，是因为它的数据早于 V9、被那次回填覆盖过。
 * 2026-09-23 把本机库清空重灌后，10 条数据范围相关的用例当场全红 —— 这一步就是它们逼出来的。
 *
 * <h2>为什么用 SQL 而不是逐行 set</h2>
 *
 * 归属链是四段 JOIN UPDATE，和 V9 里那四条**逐字相同**。用同一套 SQL 表达，
 * 将来改归属模型时两处一起看得见；拆成 Java 逐行回填反而会和迁移里的口径悄悄分叉。
 *
 * <p>{@code @Order(8)}：必须排在所有插数据的 seeder 之后。
 */
@Component
@Order(8)
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
public class ScopeAnchorSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ScopeAnchorSeeder.class);

    private final JdbcTemplate jdbc;

    public ScopeAnchorSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // 自上而下，顺序不可换（同 V9 §2 的注释）
        int loc = jdbc.update("""
                UPDATE loc_location l JOIN loc_site s ON s.site_no = l.site_no
                   SET l.agent_no = s.agent_no
                 WHERE l.agent_no IS NULL AND s.agent_no IS NOT NULL""");
        int cab = jdbc.update("""
                UPDATE dev_cabinet c JOIN loc_location l ON l.location_no = c.location_no
                   SET c.site_no = l.site_no, c.agent_no = l.agent_no
                 WHERE c.site_no IS NULL OR c.agent_no IS NULL""");
        // 下面两句**重新派生而不是只补空**。原来带 `WHERE ... IS NULL`，
        // 于是一个已经写错的锚点永远不会被纠正 —— 实测测试库里有 28 行如此：
        // ST300 从 AG002 改成平台直营后，它的订单与工单仍挂着 agent_no=AG002，
        // 代理登录后照样看得到那些单。锚点是**派生数据**，陈旧就等于错误，
        // 没有「保留原值」这回事。
        //
        // ⚠️ 这只管种子库。生产上同样的陈旧会由「改站点归属」产生，
        // 而应用代码里没有任何地方重新派生这两张表的锚点 —— 那是另一件事。
        int ord = jdbc.update("""
                UPDATE ord_order o JOIN dev_cabinet c ON c.cabinet_no = o.cabinet_no
                   SET o.location_no = c.location_no, o.site_no = c.site_no, o.agent_no = c.agent_no
                 WHERE NOT (o.site_no <=> c.site_no) OR NOT (o.agent_no <=> c.agent_no)
                    OR NOT (o.location_no <=> c.location_no)""");
        int wo = jdbc.update("""
                UPDATE wo_order w JOIN dev_cabinet c ON c.cabinet_no = w.cabinet_no
                   SET w.location_no = c.location_no, w.site_no = c.site_no, w.agent_no = c.agent_no
                 WHERE NOT (w.site_no <=> c.site_no) OR NOT (w.agent_no <=> c.agent_no)
                    OR NOT (w.location_no <=> c.location_no)""");
        if (loc + cab + ord + wo > 0) {
            log.info("数据范围锚点回填：点位 {} · 机柜 {} · 订单 {} · 工单 {}", loc, cab, ord, wo);
        }
    }
}
