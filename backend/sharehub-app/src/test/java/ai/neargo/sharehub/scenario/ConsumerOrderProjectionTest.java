package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C 端订单投影（C-US / C-RE 订单列表与详情）。
 *
 * <h2>此前的三个问题，都不报错</h2>
 * ① <b>字段几乎无一相同</b>。`/mp/trade/orders` 返的是运营端那个 {@code RentOrder}，
 * 而 c-app 读 {@code amount}/{@code startAt}/{@code powerBankNo}（大写 B）/{@code siteNameBorrow}…
 * ⇒ 切到真后端时订单列表与详情**整页空白**，页面照常渲染。
 *
 * ② <b>费用明细与状态时间线后端根本没有</b>。前端类型里 {@code fees}/{@code timeline} 是必填，
 * 真后端下恒 undefined —— 费用明细那一块和时间线那一块都是空的。
 * {@code ord_event_log} 四个 service 一直在写，而读它的调用方长期是 0 个。
 *
 * ③ <b>运营干预统计漏给了消费者</b>。{@code RentOrder} 的构造器注释写着
 * 「C 端出参不含运营干预统计」，但 {@code detailForConsumer} 走 {@code detail()} → {@code enrich()}，
 * {@code waivedAmount}/{@code compensateAmount}/{@code ejectCount}/{@code lastEjectAt}
 * 其实一直在往 C 端返。注释里的约定和代码里的事实对不上，两边都不报错。
 *
 * <h2>为什么断言「列表不带 timeline」</h2>
 * 那不是偷懒，是刻意：时间线要逐单查事件表，挂在列表上就是 N+1，
 * 而订单列表恰恰是最长的那个列表。用 null 表达「这个投影没带」，
 * 与「确实一步都没有」区分开 —— 回空数组就分不清了。
 */
class ConsumerOrderProjectionTest extends ApiTestSupport {

    /**
     * 借哪台不重要，**有货**才重要。
     *
     * <p>原先写死 CAB1005 —— 共享测试库是累积的，跑过若干次之后那台会被借空，
     * 而 2026-09-25 新增的设备生命周期闸门会直接回「该设备暂不可借」。
     * 于是这条用例会在某一天开始红，而红的原因跟它要验的东西毫无关系。
     * 改成从找柜列表里现挑一台还有货的。
     */
    private String borrowableCabinet(String token) {
        for (JsonNode c : get("/mp/nearby/cabinets", token).okData()) {
            // 有货还不够，站点还得是营业中：找柜列表**刻意保留** PAUSED/WITHDRAWING 的点位
            // （那两态是「停借保还」，手里有充电宝的人正需要看到它们），从那里挑就会借不出来。
            if (c.path("availableBorrow").asInt() > 0 && "ACTIVE".equals(c.path("status").asText())) {
                return c.path("cabinetNo").asText();
            }
        }
        throw new IllegalStateException("测试库里没有任何一台有货的机柜 —— 先补种子再跑");
    }

    private String consumerToken() {
        String phone = "+9715004" + (100_000 + ThreadLocalRandom.current().nextInt(800_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }

    @Test
    @DisplayName("★★ 订单详情带得动一个页面：门店名 / 费用明细 / 状态时间线")
    void detail_carries_what_the_page_renders() {
        String token = consumerToken();
        String cabinet = borrowableCabinet(token);
        Resp rent = post("/mp/trade/orders/rent", Map.of("cabinetNo", cabinet), token);
        assertThat(rent.status).as("借出失败，后端说：%s", rent.body.path("message").asText()).isEqualTo(200);
        String orderNo = rent.okData().path("orderNo").asText();

        JsonNode d = get("/mp/trade/orders/" + orderNo, token).okData();

        // 借出端：店名取不到时至少要有机柜号，页面按 siteName ?? locationName ?? cabinetNo 兜
        assertThat(d.path("cabinetNo").asText()).as("借出机柜号").isEqualTo(cabinet);
        assertThat(d.hasNonNull("siteName") || d.hasNonNull("locationName"))
                .as("店名或点位名至少要有一个，否则列表上那一行没有标题").isTrue();

        assertThat(d.path("rentStartAt").asText()).as("借出时间（字段名是 rentStartAt 不是 startAt）").isNotBlank();
        assertThat(d.has("feeAmount")).as("费用字段名是 feeAmount 不是 amount").isTrue();

        assertThat(d.path("timeline").isArray()).as("详情要带状态时间线").isTrue();
        assertThat(d.path("timeline")).as("刚借出至少有 CREATED/IN_USE 这几步").isNotEmpty();
        for (JsonNode step : d.path("timeline")) {
            assertThat(step.path("status").asText()).isNotBlank();
            assertThat(step.has("operator"))
                    .as("时间线不该带 operator —— 那是内部员工号/系统名，不是给消费者看的")
                    .isFalse();
        }

        assertThat(d.path("fees").isArray()).as("详情要带费用明细").isTrue();
        for (JsonNode f : d.path("fees")) {
            assertThat(f.path("type").asText())
                    .as("费用明细给的是**类型码**，文案由端上按语言映射（后端返中文，阿语界面就是中文）")
                    .isIn("RENT", "WAIVE", "COMPENSATE", "DEPOSIT");
        }
    }

    @Test
    @DisplayName("★★ 运营干预统计不给消费者——注释说不给，代码一直在给")
    void ops_intervention_stats_never_reach_the_consumer() {
        String token = consumerToken();
        Resp rent = post("/mp/trade/orders/rent", Map.of("cabinetNo", borrowableCabinet(token)), token);
        assertThat(rent.status).as("借出失败，后端说：%s", rent.body.path("message").asText()).isEqualTo(200);
        String orderNo = rent.okData().path("orderNo").asText();

        JsonNode d = get("/mp/trade/orders/" + orderNo, token).okData();
        for (String leaked : new String[]{"waivedAmount", "compensateAmount", "ejectCount", "lastEjectAt"}) {
            assertThat(d.has(leaked)).as("%s 是运营干预统计，不该出现在 C 端订单详情里", leaked).isFalse();
        }
    }

    @Test
    @DisplayName("★ 列表刻意不带 timeline（挂上去就是 N+1），但该有的字段一个不少")
    void list_omits_timeline_but_keeps_the_rest() {
        String token = consumerToken();
        post("/mp/trade/orders/rent", Map.of("cabinetNo", borrowableCabinet(token)), token).okData();

        JsonNode rows = get("/mp/trade/orders", token).okData().path("list");
        assertThat(rows).isNotEmpty();
        JsonNode r = rows.get(0);
        assertThat(r.path("timeline").isNull()).as("列表不带时间线 —— null 表示「没带」，不是「一步都没有」").isTrue();
        assertThat(r.path("fees").isNull()).as("同上").isTrue();
        assertThat(r.path("cabinetNo").asText()).isNotBlank();
        assertThat(r.has("feeAmount")).isTrue();
        assertThat(r.path("durationMin").isIntegralNumber() || r.path("durationMin").isNull()).isTrue();
    }
}
