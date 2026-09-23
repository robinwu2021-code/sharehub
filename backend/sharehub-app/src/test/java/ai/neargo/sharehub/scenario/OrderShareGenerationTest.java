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
