package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代理的工作台，只能看见自己的东西。
 *
 * <h2>为什么盯工作台而不是列表页</h2>
 * 列表页（机柜/订单/工单）走的表都注册了数据范围，SQL 层自动加 {@code agent_no IN (...)}。
 * 工作台不一样：它是<b>聚合</b>，一次跨七八张表取数，
 * 其中一张漏注册就会把全平台的数据混进代理的首屏 —— 而页面照常渲染，没有任何报错。
 *
 * <h2>这不是假设</h2>
 * {@code dev_alarm} 上有 {@code agent_no}/{@code site_no}/{@code region_id} 三列，
 * 却<b>一个维度都没注册</b>，而 AGENT 持有 {@code dashboard:overview:read}。
 * 于是 {@code AlarmFactMapper.openAlarms()}（手写 @Select，无任何归属条件）
 * 取的「最近 10 条未处理告警」是<b>全平台</b>的：实测库里那 10 条分属 AG002、AG006 与平台直营。
 * 代理由此看到别家代理的柜机号与故障描述。
 *
 * <h2>断言方式</h2>
 * 不硬编码「哪台柜子属于谁」——那会随种子漂。改为拿同一个代理自己的机柜列表
 * （那个接口是被数据范围管住的）当基准集，工作台里出现的柜机必须都在里面。
 * 两个接口用同一个会话，谁漏了范围谁就对不上。
 */
class AgentDashboardScopeTest extends ApiTestSupport {

    /** 种子里 AG002 名下有柜子，且库里存在别家代理的 OPEN 告警 —— 两者都在才测得出。 */
    private static final String AGENT = "AG002";

    @Test
    @DisplayName("★★ 代理工作台的提醒条，不能出现别家代理的柜机")
    void the_agent_dashboard_only_shows_its_own_cabinets() {
        String token = loginAgent(AGENT);

        Set<String> mine = new TreeSet<>();
        for (JsonNode c : pageAll("/api/ops/cabinets", token)) {
            mine.add(c.path("cabinetNo").asText());
        }
        assertThat(mine).as("前置：这个代理名下应当有机柜，否则本用例测不出东西").isNotEmpty();

        JsonNode alerts = get("/api/ops/dashboard", token).okData().path("alerts");
        assertThat(alerts.isArray()).as("前置：工作台应当返回 alerts 数组").isTrue();

        Set<String> foreign = new TreeSet<>();
        for (JsonNode a : alerts) {
            String cab = a.path("cabinetNo").asText("");
            if (!cab.isBlank() && !mine.contains(cab)) foreign.add(cab);
        }

        assertThat(foreign).as("""
                代理的工作台提醒条里出现了不属于他的柜机 %s。
                聚合查询漏了数据范围：本代理自己的机柜列表是 %s。

                查 dev_alarm 是否在 DataScopeRegistration 里注册了 AGENT 维度 ——
                它有 agent_no 列，漏注册不会报错、不会告警，只会静默把别家的数据混进首屏。""",
                foreign, mine)
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 代理拿不到「待退款」这个数——它是全平台口径，而且退款不是代理的动作")
    void the_agent_does_not_receive_the_platform_wide_refund_count() {
        JsonNode agentTodos = get("/api/ops/dashboard", loginAgent(AGENT)).okData().path("todos");
        JsonNode adminTodos = get("/api/ops/dashboard", login("ADMIN")).okData().path("todos");

        assertThat(adminTodos.path("pendingRefunds").isNumber())
                .as("前置：全域主体应当拿得到这个数，否则本用例证明不了「只是受限主体拿不到」")
                .isTrue();

        assertThat(agentTodos.path("pendingRefunds").isNull()
                        || agentTodos.path("pendingRefunds").isMissingNode())
                .as("""
                        代理拿到了「待退款」的数。ord_refund 没有归属列，那个 COUNT 是**全平台**的
                        （实测超管与代理都是 189）——不是少算，是把平台的待办摆在伙伴的首屏上。

                        不能靠前端藏：藏起来的数字仍在响应体里，抓一次接口就看到了。
                        判据在 ReportServiceImpl.dashboard，按当前会话的数据范围裁。""")
                .isTrue();

        // 另两格必须还在：它们被数据范围管住，是这个代理自己的，删了是丢信息
        assertThat(agentTodos.path("pendingWorkOrders").isNumber()).as("待派单仍应返回").isTrue();
        assertThat(agentTodos.path("pendingWithdrawals").isNumber()).as("待审提现仍应返回").isTrue();
    }
}
