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

        /*
         * ⚠️ **一张表登记了一个维度，就不该漏掉它有列可用的其它已提供维度**。
         * handler 是 fail-closed：REGION 主体查一张「登记了 AGENT/SITE 却没登记 REGION」的表，
         * 拿到的是 1=0 —— 不是少几行，是一行都没有，且不报错。
         *
         * 本仓提供的档位是 ALL/REGION/SITE/AGENT/SELF（ops-web 的 DataScope）。
         * dev_cabinet / ord_order / wo_order 三张表都有 region_id，
         * 而此前只有 loc_site 登记了 REGION —— 按区域收敛的员工能看到站点，
         * 却在机柜/订单/工单三个页面一条都看不到。今天没人配 REGION（库里只有 ALL 与 SITE），
         * 所以还没人踩到；档位在下拉里摆着，踩到只是时间问题。
         * 这与 LOCATION 那次是同一个病：**档位提供了，而承载它的表没准备好**。
         *
         * location_no 不登记：LOCATION 已不在提供的档位里（那次清理的结论），
         * 给一个没人能选的维度登记锚点只会让下一个人以为它还在用。
         */
        // —— 场地：站点是归属的权威源，点位随站点 ——
        registry.register("loc_site", Map.of(
                "AGENT", "agent_no",
                "REGION", "region_id",
                "SITE", "site_no"));

        registry.register("loc_location", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no"));

        // —— 运营核心流程批次 C（V107）：只挂站点维度 —— 勘测 / 资产差异 / 结算调整项是平台运营与财务的活，
        // 没有代理列；代理身份查它们按 fail-closed 一行都看不到，这正是想要的（代理不参与撤场结清与仓库对账）
        registry.register("loc_site_survey", Map.of("SITE", "site_no"));
        registry.register("inv_asset_diff", Map.of("SITE", "site_no"));
        registry.register("stl_adjustment", Map.of("SITE", "site_no"));

        // 批次 F（V110）：清退单与运维考核归代理 —— 代理身份只看得到自己的
        registry.register("agt_exit", Map.of("AGENT", "agent_no"));
        registry.register("agt_ops_assessment", Map.of("AGENT", "agent_no"));

        // —— 设备：机柜归属随点位 ——
        registry.register("dev_cabinet", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no",
                "REGION", "region_id"));

        // —— 交易：订单归属随借出机柜（下单时快照，不随设备后续调拨变动）——
        // SELF → c_user_no 必须注册，理由见类注释「关于 SELF」一节：handler 是 fail-closed，
        // 漏了它 C 端查自己的订单会被拼成 1=0，一条都看不到。
        registry.register("ord_order", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no",
                "SELF", "c_user_no",
                "REGION", "region_id"));

        // —— 运维：工单归属随设备 ——
        registry.register("wo_order", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no",
                "REGION", "region_id"));

        /*
         * —— 告警 ——
         *
         * ⚠️ **工作台是聚合，一张表漏注册就够了**。AGENT 持有 dashboard:overview:read，
         * 而 /api/ops/dashboard 的提醒条取自 AlarmFactMapper.openAlarms()
         * （手写 @Select，无任何归属条件）。本表此前一个维度都没注册 ——
         * 于是代理的首屏列的是**全平台**最近 10 条未处理告警：
         * 实测 AG002 登录后看到 CAB1000/CAB1003/CAB1005，其中 CAB1005 是 AG006 的。
         * 列表页没事（那些表都注册了），偏偏是首屏漏的，而它不报错也不告警。
         *
         * 三个维度都登记：本表 agent_no / site_no / region_id 三列俱全，
         * 而 handler 是 fail-closed —— 只登记 AGENT 的话，按区域或站点收敛的运营人员
         * 会一条告警都看不到（见本类开头的说明）。
         *
         * SELF 有意不登记：本表没有 c_user_no，C 端也不该读运营告警。
         * 将来若真要读，用 DataScopeContext.executeWithoutScope(...) 显式豁免，
         * **不要**给它编一个假的 SELF 锚点。
         */
        registry.register("dev_alarm", Map.of(
                "AGENT", "agent_no",
                "SITE", "site_no",
                "REGION", "region_id"));

        /*
         * —— 资金四表（V58）——
         *
         * 此前这里写着「需 handler 支持『带条件的锚点』」而搁置：registry 只能登记
         * 「维度 → 列」一对一，表达不了 `payee_type='AGENT' AND payee_no IN (...)`。
         *
         * V58 把那个条件**挪进了列定义** —— agent_no 是生成列
         * `CASE WHEN payee_type='AGENT' THEN payee_no END`，VENUE 行为 NULL，
         * 而 IN (...) 天然不匹配 NULL。于是不必改共享框架，也不会误伤 VENUE 的行。
         *
         * 搁置的代价是实打实的：代理端实名登录打通后实测，一个名下零条资金记录的新代理
         * 能读到分润明细 108 行、收款账户 150 行（全平台的户名与银行账号掩码）。
         * 回归见 AgentDataScopeProbeTest。
         */
        registry.register("share_record", Map.of("AGENT", "agent_no"));
        registry.register("stl_payout_account", Map.of("AGENT", "agent_no"));
        // 这两张代理当前还没有读取权限码，先注册：漏的后果是「哪天给了码就立刻泄露」，
        // 而多注册的后果只是 fail-closed 方向的「看不到」——两种错的代价不对称。
        registry.register("stl_withdrawal", Map.of("AGENT", "agent_no"));
        registry.register("stl_settlement", Map.of("AGENT", "agent_no"));

        /*
         * —— 运营核心流程（2026-09-25，TDD-运营核心流程）——
         *
         * 带归属列的新表全部登记（handler fail-closed：表上有哪一维的列就必须登记哪一维）。
         * 合同与两张日志表没有 agent_no：代理维度恒空 —— 与现状一致（代理没有合同读权限）。
         */
        registry.register("loc_contract", Map.of("SITE", "site_no"));
        registry.register("loc_site_status_log", Map.of("SITE", "site_no"));
        registry.register("sys_file", Map.of("AGENT", "agent_no"));
        registry.register("dev_protection", Map.of("AGENT", "agent_no", "SITE", "site_no"));
        registry.register("dev_trial_rent", Map.of("AGENT", "agent_no", "SITE", "site_no"));
        registry.register("dev_alarm_todo", Map.of("AGENT", "agent_no", "SITE", "site_no"));
        registry.register("dev_alarm_site_profile", Map.of("SITE", "site_no"));

        // ⚠️ 待表建好后补注册（[TDD §5.2] 清单剩余项）：
        //   dev_alarm → AGENT: agent_no, SITE: site_no
    }
}
