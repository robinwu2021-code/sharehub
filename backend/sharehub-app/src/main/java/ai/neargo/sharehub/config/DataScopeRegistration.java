package ai.neargo.sharehub.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * powerbank 数据范围表注册（neargo {@link DataScopeRegistrar}）：声明各表在各维度用哪列过滤。
 * 由 {@code DataScopeHandler} 在 SQL 层自动追加 {@code agent_no IN (...)} 等条件，业务代码零 where。
 *
 * <p><b>未注册的表 = 全局放行</b> —— 这是本机制最危险的性质：漏注册不会报错、不会告警，
 * 只会静默地让越权数据流出去。所以新增带归属语义的表时，<b>注册是建表的一部分，不是可选项</b>。
 *
 * <p><b>注册前提是表上真有那一列。</b>此前只有 {@code loc_site} 一张表被注册，
 * 正是因为 {@code ord_order}/{@code wo_order}/{@code dev_cabinet}/{@code loc_location}
 * 都还没有 {@code agent_no} —— 列已由 {@code ddl/pb_core-v2-datascope.sql} 补齐并按归属链回填。
 *
 * <p><b>⚠️ 关于 SELF —— handler 是 fail-closed，这决定了注册策略</b>：
 * {@code DataScopeHandler} 在「当前 spec 的维度在本表锚点里找不到列」时，
 * 生成的是 <b>{@code 1=0}（全部拒绝）而不是放行</b>。
 * 所以只要一张表被注册，<b>所有可能访问它的主体的维度都必须登记</b>，漏一个就是那类主体全瞎。
 *
 * <p>C 端会话的 spec 是 {@code SELF}（见 {@code ConsumerAuthService}）。
 * {@code ord_order}（原 {@code ord_order}，ADR-018 拆表后改名）一旦注册了 AGENT/SITE 却漏了 SELF，
 * C 端查「我的订单」立刻变成 {@code 1=0} ——
 * 这不是假设，是 B1 落地时 {@code ConsumerRentFlowTest} 实际挂掉的原因。
 * 登记 {@code SELF → c_user_no} 后，属主过滤同时获得了 SQL 层的防 IDOR 兜底（纵深防御）。
 *
 * <p><b>C 端要浏览的运营数据表是个未解问题</b>：{@code loc_site}/{@code dev_cabinet}/{@code loc_location}
 * 上没有、也不该有 {@code c_user_no} 这类 SELF 锚点。等 {@code /mp/nearby/**} 落地时，
 * 附近网点查询会因同样的 fail-closed 规则拿到空集。届时应在那几个查询上用
 * {@code DataScopeContext.executeWithoutScope(...)} 显式豁免 —— <b>而不是</b>给这些表编一个假的 SELF 锚点。
 *
 * <p><b>NULL 的语义</b>：{@code agent_no IS NULL} 表示平台直营；{@code IN (...)} 天然不匹配 NULL，
 * 因此代理商看不到直营数据 —— 这是期望行为，不是漏过滤。
 */
@Component
public class DataScopeRegistration implements DataScopeRegistrar {

    @Override
    public void register(DataScopeTableRegistry registry) {

        // —— 场地：站点是归属的权威源，点位随站点 ——
        registry.register("loc_site", Map.of(
                "AGENT", "agent_no",
                "REGION", "region_id",
                "SITE", "site_no"));

        registry.register("loc_location", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no"));

        // —— 设备：机柜归属随点位 ——
        registry.register("dev_cabinet", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no"));

        // —— 交易：订单归属随借出机柜（下单时快照，不随设备后续调拨变动）——
        // SELF → c_user_no 必须注册，理由见类注释「关于 SELF」一节：handler 是 fail-closed，
        // 漏了它 C 端查自己的订单会被拼成 1=0，一条都看不到。
        registry.register("ord_order", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no",
                "SELF", "c_user_no"));

        // —— 运维：工单归属随设备 ——
        registry.register("wo_order", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no"));

        // ⚠️ 待表建好后补注册（[TDD §5.2] 清单剩余项，当前开发库尚无这三张表）：
        //   dev_alarm      → AGENT: agent_no, SITE: site_no
        //   share_record   → AGENT: payee_no（仅 payee_type='AGENT' 时成立，
        //                    需 handler 支持「带条件的锚点」，直接注册会误伤 VENUE 维度的行）
        //   stl_settlement → AGENT: payee_no（同上）
    }
}
