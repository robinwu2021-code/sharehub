package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **订单结算 → 分润明细**（MVP 硬阻塞 3）。
 *
 * <p>在补上这条链路之前，{@code share_record} 只有演示数据在写、{@code SettlementServiceImpl}
 * 只读不产 —— 订单产生了收入却不会变成任何人的分成，结算单永远是空的。
 * 这个缺陷在界面上**完全看不出来**：每一页都正常渲染，只是分润明细里少了本该有的行。
 *
 * <h2>为什么生成逻辑走服务层而不是 HTTP</h2>
 *
 * 走 HTTP 要先造一笔**金额非 0** 的订单，而新借的单当场归还时长为 0、落在免费时长内，
 * 金额就是 0（这本身是对的，见下面那条用例）。唯一能造出金额的是种子里的在途单，
 * 但那是共享数据、归还一次就没了，测试不可重跑。
 * 所以生成逻辑直接调 {@link ShareGenerator}：**真库、真 Port、真规则解析**，
 * 只有事件是合成的 —— 而事件本身的发布由 {@code ConsumerRentFlowTest} 的归还闭环覆盖。
 */
class OrderShareGenerationTest extends ApiTestSupport {

    /** ST305 挂着场地方 VEN300，且有生效合同 CT405（V38 回填）。 */
    private static final String SITE = "ST305";
    private static final String AGENT = "AG006";

    /** 责任分账用另一个站点：ST305 被上面几条用例占着，往它身上加责任行会改掉它们的期望条数。 */
    private static final String MULTI_SITE = "ST307";
    private static final String CABINET = "CAB1005";
    private static final String PHONE = "+971500009101";

    @Autowired
    private ShareGenerator generator;

    private String orderNo(String tag) {
        // 每次运行用新单号：唯一键 uk_srec_order_payee 会拦住重复，
        // 固定单号会让第二次运行误判成「没生成」
        return "ORDTEST" + tag + System.nanoTime();
    }

    private OrderSettledEvent event(String orderNo, BigDecimal gross) {
        return new OrderSettledEvent(orderNo, CABINET, SITE, AGENT, gross, "AED",
                LocalDate.now().toString().substring(0, 7));
    }

    private JsonNode sharesOf(String orderNo, String admin) {
        return get("/api/trade/share-records?page=1&size=50&keyword=" + orderNo, admin)
                .okData().path("list");
    }

    @Test
    void settled_order_generates_share_records() {
        String admin = login("ADMIN");
        // 代理分成需要一条 AGENT 规则。测试自己建，不依赖种子 ——
        // 种子里的规则挂在 AG001 上，而这个站点归 AG006
        post("/api/trade/share-rules", Map.of("dimension", "AGENT", "payeeNo", AGENT,
                "payeeName", "测试代理 " + AGENT, "mode", "LEDGER", "rate", 0.2, "priority", 5), admin).okData();

        String no = orderNo("A");
        assertThat(generator.generate(event(no, new BigDecimal("100.00"))))
                .as("100 元的单应至少分出场地方与代理两条").isEqualTo(2);

        JsonNode shares = sharesOf(no, admin);
        assertThat(shares).as("生成了却查不到 = 写入与查询口径对不上").hasSize(2);
        for (JsonNode r : shares) {
            assertThat(r.path("payeeNo").asText()).as("分成方为空就不知道这笔钱是谁的").isNotBlank();
            assertThat(r.path("rate").asDouble()).as("比例是快照，不能为 0").isGreaterThan(0);
            assertThat(r.path("status").asText()).isEqualTo("PENDING");
            // 分账不得超过基数：三方之和多出一分，对账时要查很久
            assertThat(r.path("amount").asDouble()).isLessThanOrEqualTo(100.00);
        }
        // 场地方按**合同** CT405 的 0.20 分（不是按 share_rule）—— 这是 D2 的落地口径
        JsonNode venue = shares.get(0).path("payeeType").asText().equals("VENUE") ? shares.get(0) : shares.get(1);
        assertThat(venue.path("rate").asDouble()).as("场地方比例应取自进场合同").isEqualTo(0.20);
        assertThat(venue.path("amount").asDouble()).isEqualTo(20.00);
    }

    @Test
    void rule_without_payee_no_is_refused() {
        // 取价按 payee_no **精确匹配**。没有编号的规则一条都命中不了 ——
        // 运营在界面上配完看着好好的，分账时那个分成方却拿不到钱，而且不报错。
        // 这是本仓库「按名字连」那类错误里最贵的一种：错的不是显示，是钱。
        String admin = login("ADMIN");
        assertThat(post("/api/trade/share-rules", Map.of("dimension", "AGENT",
                "payeeName", "只有名字没有编号", "mode", "LEDGER", "rate", 0.2), admin).status)
                .as("缺分成方编号必须当场拒，而不是存下一条永远不生效的规则").isEqualTo(400);
    }

    @Test
    void free_order_generates_nothing() {
        // 免单与全额券抵扣没有真实现金流。按应收分账 = 用平台的钱替用户给场地方付分成。
        assertThat(generator.generate(event(orderNo("F"), BigDecimal.ZERO))).isZero();
    }

    @Test
    void broken_ownership_chain_generates_nothing_and_does_not_throw() {
        // 机柜没绑点位/站点时，「这笔钱本该分给谁」没人知道。跳过并留痕，不能抛异常
        // 把整条归还链路带崩 —— 用户已经把充电宝还回去了。
        OrderSettledEvent orphan = new OrderSettledEvent(orderNo("O"), CABINET, null, null,
                new BigDecimal("50.00"), "AED", LocalDate.now().toString().substring(0, 7));
        assertThat(generator.generate(orphan)).isZero();
    }

    @Test
    void generation_is_idempotent() {
        String no = orderNo("I");
        int first = generator.generate(event(no, new BigDecimal("80.00")));
        assertThat(first).isPositive();
        // outbox 重投是设计内的：第二次必须一条都不新增，且不报错
        assertThat(generator.generate(event(no, new BigDecimal("80.00")))).isZero();
        assertThat(sharesOf(no, login("ADMIN"))).hasSize(first);
    }

    // ══════════ 按责任分账（A2-2 / ADR-027 §四）══════════

    private OrderSettledEvent eventAt(String site, String orderNo, BigDecimal gross) {
        return new OrderSettledEvent(orderNo, CABINET, site, null, gross, "AED",
                LocalDate.now().toString().substring(0, 7));
    }

    /** 建一条带依据的分润规则，返回规则号。 */
    private String rule(String admin, String agentNo, String basis, double rate) {
        return post("/api/trade/share-rules", Map.of("dimension", "AGENT", "payeeNo", agentNo,
                "payeeName", "测试伙伴 " + agentNo, "basis", basis, "mode", "LEDGER",
                "rate", rate, "priority", 5), admin).okData().path("ruleNo").asText();
    }

    /** 给站点配一行责任，返回行 id（便于用例收尾时撤掉）。 */
    private long responsibility(String admin, String site, String agentNo, String role) {
        return post("/api/ops/sites/" + site + "/agents",
                Map.of("agentNo", agentNo, "role", role), admin).okData().path("id").asLong();
    }

    private void unresponsibility(String admin, String site, long id) {
        post("/api/ops/sites/" + site + "/agents/" + id + "/remove", Map.of(), admin).okData();
    }

    @Test
    void each_responsibility_gets_its_own_record_with_its_own_rate() {
        // 一个站点上「谁出的钱、谁在维护」不是同一个人。压成一条的代价不是不精确，
        // 是**不可追溯** —— 结算争议时说不清这 8% 里几个点是运维、几个点是出资。
        String admin = login("ADMIN");
        rule(admin, "AG008", "OPERATE", 0.08);
        rule(admin, "AG002", "INVEST", 0.05);
        // 两行都由用例自己配。**不依赖 V52 的回填**：回填跑在迁移里、种子插在迁移之后，
        // 所以全新测试库里 loc_site_agent 是空的 —— 依赖它的用例只在「迁移过的老库」上绿。
        long operate = responsibility(admin, MULTI_SITE, "AG008", "OPERATE");
        long invest = responsibility(admin, MULTI_SITE, "AG002", "INVEST");
        try {
            String no = orderNo("R");
            generator.generate(eventAt(MULTI_SITE, no, new BigDecimal("100.00")));

            JsonNode shares = sharesOf(no, admin);
            Map<String, Double> byBasis = new java.util.HashMap<>();
            for (JsonNode r : shares) {
                if (!"AGENT".equals(r.path("dimension").asText())) continue;
                byBasis.put(r.path("basis").asText(), r.path("amount").asDouble());
            }
            assertThat(byBasis).as("出资与运维各应有自己的一条，而不是合并成一条")
                    .containsOnlyKeys("OPERATE", "INVEST");
            assertThat(byBasis.get("OPERATE")).as("运维 8%").isEqualTo(8.00);
            assertThat(byBasis.get("INVEST")).as("出资 5%").isEqualTo(5.00);
        } finally {
            unresponsibility(admin, MULTI_SITE, invest);
            unresponsibility(admin, MULTI_SITE, operate);
        }
    }

    @Test
    void refer_is_not_paid_per_order() {
        // 牵线的对价是「把关系介绍过来」这个一次性动作。按逐单比例付会变成
        // **介绍一次、分十年** —— 它走签约事件（A2-4），不在逐单分润里。
        String admin = login("ADMIN");
        rule(admin, "AG008", "OPERATE", 0.08);
        rule(admin, "AG005", "REFER", 0.03);
        // 配一行 OPERATE，让站点走「有责任行」的分支 —— 否则测的是回落路径，REFER 根本没被读到
        long operate = responsibility(admin, MULTI_SITE, "AG008", "OPERATE");
        long refer = responsibility(admin, MULTI_SITE, "AG005", "REFER");
        try {
            String no = orderNo("N");
            generator.generate(eventAt(MULTI_SITE, no, new BigDecimal("100.00")));
            for (JsonNode r : sharesOf(no, admin)) {
                assertThat(r.path("basis").asText()).as("REFER 不该出现在逐单分润里").isNotEqualTo("REFER");
            }
        } finally {
            unresponsibility(admin, MULTI_SITE, refer);
            unresponsibility(admin, MULTI_SITE, operate);
        }
    }

    @Test
    void site_without_responsibility_rows_falls_back_instead_of_paying_nothing() {
        // 责任表是逐站点配的。没配的站点不能因此不分账 ——
        // 那是**静默少付合作伙伴**，与「静默免单」同一性质。
        String admin = login("ADMIN");
        post("/api/trade/share-rules", Map.of("dimension", "AGENT", "payeeNo", AGENT,
                "payeeName", "测试代理 " + AGENT, "mode", "LEDGER", "rate", 0.2, "priority", 5), admin).okData();

        // ST303 没有 agent_no，故 V52 没给它回填责任行；事件自带 agentNo 走回落分支
        String no = orderNo("B");
        OrderSettledEvent e = new OrderSettledEvent(no, CABINET, "ST303", AGENT,
                new BigDecimal("100.00"), "AED", LocalDate.now().toString().substring(0, 7));
        generator.generate(e);

        JsonNode agent = null;
        for (JsonNode r : sharesOf(no, login("ADMIN"))) {
            if ("AGENT".equals(r.path("dimension").asText())) agent = r;
        }
        assertThat(agent).as("没配责任行 ≠ 不分账").isNotNull();
        assertThat(agent.path("basis").asText()).as("回落记为运维分成（今天那条的实际含义）")
                .isEqualTo("OPERATE");
    }

    @Test
    void two_responsibilities_of_one_partner_still_idempotent() {
        // 幂等键从 (order,dimension,payee) 放宽到含 basis。放宽是为了让同一个伙伴
        // 在一单里有出资 + 运维两条 —— 但**重投仍然必须一条都不新增**。
        String admin = login("ADMIN");
        rule(admin, "AG008", "OPERATE", 0.08);
        rule(admin, "AG008", "INVEST", 0.05);
        long operate = responsibility(admin, MULTI_SITE, "AG008", "OPERATE");
        long invest = responsibility(admin, MULTI_SITE, "AG008", "INVEST");
        try {
            String no = orderNo("D");
            int first = generator.generate(eventAt(MULTI_SITE, no, new BigDecimal("100.00")));
            assertThat(first).as("同一伙伴的两项责任应各分一条").isGreaterThanOrEqualTo(2);
            assertThat(generator.generate(eventAt(MULTI_SITE, no, new BigDecimal("100.00"))))
                    .as("事件重投必须一条都不新增").isZero();
            assertThat(sharesOf(no, admin)).hasSize(first);
        } finally {
            unresponsibility(admin, MULTI_SITE, invest);
            unresponsibility(admin, MULTI_SITE, operate);
        }
    }

    @Test
    void revoked_responsibility_can_be_added_back() {
        // 撤销是逻辑删除，而唯一键不含 deleted —— 那一行仍占着键。
        // 没有「复活」的话，运营撤销一条责任之后再想加回来就是一个 500，
        // 而且提示里什么都看不出来（常规查询看不见已撤销的行）。
        String admin = login("ADMIN");
        long first = responsibility(admin, MULTI_SITE, "AG003", "INVEST");
        unresponsibility(admin, MULTI_SITE, first);
        long again = responsibility(admin, MULTI_SITE, "AG003", "INVEST");
        try {
            assertThat(again).as("复活的是原来那一行，历史不丢").isEqualTo(first);
        } finally {
            unresponsibility(admin, MULTI_SITE, again);
        }
    }

    @Test
    void instant_return_is_free_so_no_share_is_generated() {
        // 借了就还 = 时长 0 分钟、落在免费时长内、金额 0 —— 走完整 HTTP 闭环确认
        // 这条链路端到端不报错，且**正确地**没有分润（而不是"分润没生成"的缺陷）。
        String otp = post("/mp/auth/otp", Map.of("phone", PHONE), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", PHONE, "otp", otp), null)
                .okData().path("token").asText();
        String admin = login("ADMIN");

        String no = post("/mp/trade/orders/rent", Map.of("cabinetNo", CABINET), token)
                .okData().path("orderNo").asText();
        assertThat(post("/internal/trade/orders/" + no + "/return",
                Map.of("returnCabinetNo", CABINET), admin).okData().path("ok").asBoolean()).isTrue();

        assertThat(get("/mp/trade/orders/" + no, token).okData().path("amount").asDouble())
                .as("前提：当场归还应是免费单").isZero();
        assertThat(sharesOf(no, admin)).as("免费单不该产生分润").isEmpty();
    }
}
