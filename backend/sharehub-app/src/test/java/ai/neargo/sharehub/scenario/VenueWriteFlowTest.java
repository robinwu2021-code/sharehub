package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **场地方写入口**——「场地方 → 合同 → 站点 → 责任 → 分账」这条链的第一环。
 *
 * <p>此前 {@code loc_venue} 只有种子在写：运营能签合同、能配站点责任、能按责任分账，
 * <b>却建不出一个新场地方</b>。前端的「新增/编辑」按钮在 {@code USE_MOCK=0} 下直接 404。
 *
 * <p>同时覆盖一个**「成功了但什么都没发生」**的缺陷：进件审核通过时的建场地方走
 * {@code VenueCreator} 接缝，而它此前是只取号、不落行的占位实现 ——
 * 审核成功、venueNo 也回填了，场地方列表里却查无此人。
 */
class VenueWriteFlowTest extends ApiTestSupport {

    private Map<String, Object> venue(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("contact", "+97140000000");
        m.put("industry", "购物中心");
        return m;
    }

    /**
     * 翻页找，别只看第一页 —— 测试库是累积的（见 application.properties），
     * 现在 68 行还够，越过 200 之后这里会报「建完查不到」，而真实原因是分页。
     *
     * <p>为什么不用 {@code keyword} 过滤：venue 的 keyword **只匹配 name**
     * （{@code LocService.pageVenues}），按编号传进去一条都匹配不上。
     * 而按名字过滤同样不行 —— {@code venue_can_be_edited} 就在改名字。
     */
    private JsonNode findVenue(String admin, String venueNo) {
        for (int page = 1; page <= 100; page++) {   // 上限兜底，别让接口异常变成死循环
            JsonNode body = get("/api/ops/venues?page=" + page + "&size=200", admin).okData();
            JsonNode list = body.path("list");
            if (list.isEmpty()) return null;
            for (JsonNode v : list) {
                if (venueNo.equals(v.path("venueNo").asText())) return v;
            }
            if ((long) page * 200 >= body.path("total").asLong()) return null;
        }
        return null;
    }

    @Test
    void venue_can_be_created_and_shows_up_in_the_list() {
        String admin = login("ADMIN");
        String no = post("/api/ops/venues", venue("写入口测试商场"), admin)
                .okData().path("venueNo").asText();
        assertThat(no).as("服务端取号").startsWith("VEN");
        assertThat(findVenue(admin, no)).as("建完要能在列表里查得到").isNotNull();
    }

    @Test
    void venue_can_be_edited() {
        String admin = login("ADMIN");
        String no = post("/api/ops/venues", venue("改名前"), admin).okData().path("venueNo").asText();
        post("/api/ops/venues/" + no, venue("改名后"), admin).okData();
        assertThat(findVenue(admin, no).path("name").asText()).isEqualTo("改名后");
    }

    @Test
    void venue_name_is_required() {
        // 没有名字的场地方在任何列表里都是一行空白，且没人知道它是谁。
        Map<String, Object> bad = venue("");
        assertThat(post("/api/ops/venues", bad, login("ADMIN")).status).isEqualTo(400);
    }

    @Test
    void same_name_is_allowed() {
        // 同一个品牌在不同城市各有主体、同名不同主体是常态（种子里 Emaar Malls 就出现两次）。
        // 靠名字判重会把合法的第二家挡在门外，而运营只能改名绕过去 —— 绕出来的名字日后没人认得。
        String admin = login("ADMIN");
        String a = post("/api/ops/venues", venue("同名商场"), admin).okData().path("venueNo").asText();
        String b = post("/api/ops/venues", venue("同名商场"), admin).okData().path("venueNo").asText();
        assertThat(b).as("同名应各自成号").isNotEqualTo(a);
    }

    @Test
    void editing_does_not_resurrect_an_archived_venue() {
        // 归档/恢复有专门端点。编辑表单顺手把一个已归档的场地方复活，
        // 是「改了个名字，结果它又回到列表里」这种没人预料的副作用。
        String admin = login("ADMIN");
        String no = post("/api/ops/venues", venue("待归档商场"), admin).okData().path("venueNo").asText();
        post("/api/ops/venues/" + no + "/archive", Map.of(), admin).okData();
        assertThat(findVenue(admin, no)).as("前提：归档后默认列表查不到").isNull();

        post("/api/ops/venues/" + no, venue("待归档商场改名"), admin).okData();
        assertThat(findVenue(admin, no)).as("编辑不该把归档状态改掉").isNull();
    }

    // ——— 进件（运营代录）———

    private Map<String, Object> onboarding(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("venueName", name);
        m.put("contact", "+97141111111");
        m.put("industry", "写字楼");
        return m;
    }

    @Test
    void onboarding_can_be_recorded_by_ops() {
        String admin = login("ADMIN");
        JsonNode ob = post("/api/ops/venue-onboardings", onboarding("代录进件"), admin).okData();
        assertThat(ob.path("onboardingNo").asText()).startsWith("OB");
        assertThat(ob.path("status").asText()).as("新录的进件一律待审").isEqualTo("PENDING");
    }

    @Test
    void reviewed_onboarding_content_is_frozen() {
        // 审核结论是对「当时那份内容」做的。事后改内容，结论就对不上它审过的东西了。
        String admin = login("ADMIN");
        String no = post("/api/ops/venue-onboardings", onboarding("审后改不动"), admin)
                .okData().path("onboardingNo").asText();
        post("/api/ops/venue-onboardings/" + no + "/review",
                Map.of("approve", true), admin).okData();

        assertThat(post("/api/ops/venue-onboardings/" + no, onboarding("偷偷改掉"), admin).status)
                .as("已审核的进件内容不可再改").isEqualTo(400);
    }

    @Test
    void approving_an_onboarding_really_creates_the_venue() {
        // 这条是本次修掉的「成功了但什么都没发生」：占位实现只取号不落行 ——
        // 审核成功、venueNo 也回填了，而场地方列表里查无此人，
        // 直到运营想给它签合同时才发现。每一步都显示正常，最难查的那一类。
        String admin = login("ADMIN");
        String no = post("/api/ops/venue-onboardings", onboarding("进件建出的商场"), admin)
                .okData().path("onboardingNo").asText();
        JsonNode reviewed = post("/api/ops/venue-onboardings/" + no + "/review",
                Map.of("approve", true), admin).okData();

        String venueNo = reviewed.path("venueNo").asText();
        assertThat(venueNo).as("通过后要回填场地方号").startsWith("VEN");

        JsonNode v = findVenue(admin, venueNo);
        assertThat(v).as("回填的场地方号必须真有对应的行").isNotNull();
        assertThat(v.path("name").asText()).as("名称应取自进件").isEqualTo("进件建出的商场");
    }
}
