package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 归档 → 取消归档的往返守卫。
 *
 * <p><b>为什么值得一条独立测试</b>：这里出过一个静默失效的 bug —— MyBatis-Plus 默认更新策略
 * 是 {@code NOT_NULL}，{@code updateById} 会跳过值为 null 的字段，于是「取消归档」把
 * {@code archivedAt} 置 null 后什么都没写进去。表现是<b>接口 200、数据不变</b>：
 * 归档能进不能出，页面上那行永远躺在归档列表里，而没有任何一处报错。
 *
 * <p>断言的是<b>往返</b>而不是单步：只测 archive 会通过（它写的是非 null 值），
 * 只有走完 unarchive 再读回来才抓得到。
 */
class ArchiveRoundTripTest extends ApiTestSupport {

    @Test
    void unarchive_actually_clears_the_timestamp() {
        String token = login("ADMIN");
        JsonNode plans = get("/api/trade/price-plans?page=1&size=1", token).okData();
        assumeTrue(plans.path("total").asLong() > 0, "无计价方案数据 —— 跳过");
        String planNo = plans.path("list").get(0).path("planNo").asText();

        JsonNode archived = post("/api/trade/price-plans/" + planNo + "/archive", null, token).okData();
        assertThat(archived.path("archivedAt").asText(null))
                .as("归档应盖上时间戳").isNotNull().isNotBlank();

        JsonNode restored = post("/api/trade/price-plans/" + planNo + "/unarchive", null, token).okData();
        assertThat(restored.path("archivedAt").isNull())
                .as("取消归档必须真的清空 archivedAt（NOT_NULL 更新策略会静默跳过 null 字段）")
                .isTrue();

        // 再从列表读回一次：出参对了但库里没改的话，这一步才会露馅
        JsonNode reread = get("/api/trade/price-plans?page=1&size=50", token).okData();
        JsonNode row = null;
        for (JsonNode r : reread.path("list")) {
            if (planNo.equals(r.path("planNo").asText())) row = r;
        }
        assertThat(row).as("取消归档后该行应回到默认列表（默认过滤已归档）").isNotNull();
        assertThat(row.path("archivedAt").isNull()).as("库里 archived_at 应确实为 NULL").isTrue();
    }
}
