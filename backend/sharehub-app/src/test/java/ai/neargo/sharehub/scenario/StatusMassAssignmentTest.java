package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **状态只能由状态机改，保存端点不许碰它。**
 *
 * <h2>这是同一个洞的另一半</h2>
 * 2026-09-25 前后给营销活动 / 广告活动 / 推送都建了迁移表、上了两端的边卡口、
 * 补了 mock 守卫 —— 守的全是<b>动作端点</b>那条路（{@code /{no}/{action}}、
 * {@code /send}、{@code /finish}）。
 *
 * <p>而这三个对象的<b>实体本身就是 {@code @RequestBody}</b>，
 * MyBatis-Plus 的 {@code updateById} 只写非 null 字段 ——
 * 于是保存端点是第二条路：
 *
 * <pre>{@code  POST /api/user/campaigns/CMP001   {"status":"ENDED"}}</pre>
 *
 * 一条边都不用走，直接落终态。{@code AbstractCrudService} 的集中加固只锁
 * {@code tenantId}/{@code deleted}/{@code createdAt}/{@code createdBy} 四个，
 * 域字段按设计「由各 service 覆写 {@code beforeUpdate} 显式保护」，而这三个都没覆写。
 *
 * <p>⇒ <b>卡口守住一条路，另一条敞着，等于没守。</b>
 *
 * <h2>为什么只有这三个</h2>
 * 一开始以为有 8 个（凡 status 可写的实体都算），逐个查下来不是：
 * <ul>
 *   <li>{@code InvTransfer}：保存路径<b>本来就过状态机</b>
 *       （{@code InventoryTransferServiceImpl} 拿目标状态反查事件），状态该走这里；</li>
 *   <li>{@code UsrFreeWhitelist}：{@code FreeWhitelistServiceImpl} 已经写了
 *       {@code body.setStatus(current.getStatus())} —— 本类的修法就是照它抄的；</li>
 *   <li>{@code AgtAccount} / {@code NotifyTemplate} / {@code DevAlarmRule}：
 *       status 是启用/停用<b>开关</b>，没有状态机也没有专用端点，保存就是正当入口。</li>
 * </ul>
 * 「凡 status 可写就是洞」会把上面四类一起判错，然后要么误锁功能、要么写一堆假豁免。
 */
class StatusMassAssignmentTest extends ApiTestSupport {

    private static String uniq(String p) {
        return p + System.nanoTime();
    }

    // ───────────────────────── 营销活动 ─────────────────────────

    @Test
    @DisplayName("★★ 活动：保存端点传 status 不生效——终态只能由 end 动作走到")
    void campaign_status_cannot_be_set_through_save() {
        String admin = login("ADMIN");

        Map<String, Object> create = new HashMap<>();
        create.put("name", uniq("批量赋值探针-活动-"));
        create.put("kind", "DISCOUNT");
        String no = post("/api/user/campaigns", create, admin).okData().path("campaignNo").asText();
        assertThat(statusOfCampaign(admin, no)).as("建单落 DRAFT").isEqualTo("DRAFT");

        // 绕过状态机：直接把 DRAFT 推到终态 ENDED
        Map<String, Object> evil = new HashMap<>();
        evil.put("name", "批量赋值探针-活动-改名");
        evil.put("status", "ENDED");
        post("/api/user/campaigns/" + no, evil, admin);

        assertThat(statusOfCampaign(admin, no))
                .as("状态必须原地不动 —— 保存端点不是状态机的入口")
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("活动：锁 status 不能把别的字段一起锁死（改名仍要生效）")
    void campaign_save_still_updates_other_fields() {
        String admin = login("ADMIN");
        Map<String, Object> create = new HashMap<>();
        create.put("name", uniq("批量赋值探针-活动B-"));
        create.put("kind", "DISCOUNT");
        String no = post("/api/user/campaigns", create, admin).okData().path("campaignNo").asText();

        String newName = uniq("改过的名字-");
        Map<String, Object> m = new HashMap<>();
        m.put("name", newName);
        post("/api/user/campaigns/" + no, m, admin).okData();

        assertThat(findInPages("/api/user/campaigns", "campaignNo", no, admin).path("name").asText())
                .as("锁的是 status，不是整个保存端点")
                .isEqualTo(newName);
    }

    @Test
    @DisplayName("活动：动作端点仍然能正常改状态")
    void campaign_action_endpoint_still_works() {
        String admin = login("ADMIN");
        Map<String, Object> create = new HashMap<>();
        create.put("name", uniq("批量赋值探针-活动C-"));
        create.put("kind", "DISCOUNT");
        create.put("endAt", "2099-12-31 23:59:59");
        String no = post("/api/user/campaigns", create, admin).okData().path("campaignNo").asText();

        post("/api/user/campaigns/" + no + "/start", Map.of(), admin).okData();
        assertThat(statusOfCampaign(admin, no)).isEqualTo("RUNNING");
    }

    private String statusOfCampaign(String admin, String no) {
        return findInPages("/api/user/campaigns", "campaignNo", no, admin).path("status").asText();
    }

    // ───────────────────────── 广告活动 ─────────────────────────

    @Test
    @DisplayName("★★ 广告活动：保存端点传 status 不生效")
    void ad_campaign_status_cannot_be_set_through_save() {
        String admin = login("ADMIN");
        Map<String, Object> create = new HashMap<>();
        create.put("advertiserNo", uniq("ADV"));   // NOT NULL 且无默认；不传会 500 而不是 400
        create.put("advertiser", uniq("探针广告主-"));
        create.put("currency", "AED");
        String no = post("/api/user/ad-campaigns", create, admin).okData().path("adNo").asText();
        assertThat(statusOfAd(admin, no)).isEqualTo("DRAFT");

        Map<String, Object> evil = new HashMap<>();
        evil.put("advertiser", "探针广告主-改名");
        evil.put("status", "ENDED");
        post("/api/user/ad-campaigns/" + no, evil, admin);

        assertThat(statusOfAd(admin, no))
                .as("广告要对广告主结算，暂停即停止计费 —— 状态更不能绕着改")
                .isEqualTo("DRAFT");
    }

    private String statusOfAd(String admin, String no) {
        return findInPages("/api/user/ad-campaigns", "adNo", no, admin).path("status").asText();
    }

    // ───────────────────────── 推送触达 ─────────────────────────

    @Test
    @DisplayName("★★ 推送：保存端点不能把草稿直接改成「已发送」")
    void push_status_cannot_be_set_through_save() {
        String admin = login("ADMIN");
        Map<String, Object> create = new HashMap<>();
        create.put("title", uniq("批量赋值探针-推送-"));
        create.put("content", "探针正文");
        create.put("channel", "APP_PUSH");
        String no = post("/api/user/push-messages", create, admin).okData().path("pushNo").asText();
        assertThat(statusOfPush(admin, no)).isEqualTo("DRAFT");

        // 直接跳终态：既没发，也留不下发送记录，而列表上显示「已发送」
        Map<String, Object> evil = new HashMap<>();
        evil.put("title", "批量赋值探针-推送-改标题");
        evil.put("status", "SENT");
        post("/api/user/push-messages/" + no, evil, admin);

        assertThat(statusOfPush(admin, no))
                .as("SENT 只能由 /send → /finish 走到，否则「已发送」是句假话")
                .isEqualTo("DRAFT");
    }

    private String statusOfPush(String admin, String no) {
        return findInPages("/api/user/push-messages", "pushNo", no, admin).path("status").asText();
    }

    // ───────────────────── 白名单是第二道 ─────────────────────

    /*
     * 2026-09-25 第二步：这三个对象已从「实体当 @RequestBody」转成写入参 DTO
     * （CampaignReq / PushReq / AdCampaignReq），DTO 里**不声明 status**。
     *
     * 于是有了两道：service 的 beforeUpdate 是**黑名单**（得有人记得写），
     * DTO 不声明是**白名单**（新人照抄也漏不掉）。上面那几条守的是合起来的效果，
     * 下面这条单独守白名单 —— 它即使在锁被人删掉之后也该拦得住。
     */

    @Test
    @DisplayName("白名单：DTO 里没有 status 这个字段，多传的键被直接丢弃")
    void unknown_keys_are_dropped_by_the_request_dto() {
        String admin = login("ADMIN");
        Map<String, Object> create = new HashMap<>();
        create.put("name", uniq("白名单探针-"));
        create.put("kind", "DISCOUNT");
        create.put("status", "RUNNING");          // 建单时就想跳过 DRAFT
        String no = post("/api/user/campaigns", create, admin).okData().path("campaignNo").asText();

        assertThat(statusOfCampaign(admin, no))
                .as("建单一律 DRAFT —— status 根本没进 DTO，更没到实体")
                .isEqualTo("DRAFT");
    }

    // ───────────────────── 必填：400 而不是 500 ─────────────────────

    @Test
    @DisplayName("必填缺失返回 400 而不是 500——此前是撞数据库 NOT NULL 约束")
    void missing_required_field_is_a_bad_request_not_a_server_error() {
        String admin = login("ADMIN");

        // ad_campaign.advertiser_no 是 NOT NULL 无默认；转 DTO 之前不传它会一路走到
        // INSERT 才炸，客户端看到的是 500「服务器错误」，而这明明是他自己少传了字段。
        Map<String, Object> noAdvertiser = new HashMap<>();
        noAdvertiser.put("advertiser", "缺编号的广告主");
        noAdvertiser.put("currency", "AED");
        assertThat(post("/api/user/ad-campaigns", noAdvertiser, admin).status)
                .as("少传必填是客户端的错，该 400")
                .isEqualTo(400);

        Map<String, Object> noName = new HashMap<>();
        noName.put("kind", "DISCOUNT");
        assertThat(post("/api/user/campaigns", noName, admin).status)
                .as("活动名必填")
                .isEqualTo(400);

        Map<String, Object> noTitle = new HashMap<>();
        noTitle.put("content", "无标题");
        assertThat(post("/api/user/push-messages", noTitle, admin).status)
                .as("推送标题必填")
                .isEqualTo(400);
    }
}
