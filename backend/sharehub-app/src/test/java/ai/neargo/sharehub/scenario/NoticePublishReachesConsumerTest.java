package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营建的公告，发布之后 C 端必须读得到。
 *
 * <h3>2026-09-27 实测：这条链是断的</h3>
 * 运营台的「发布」按钮发的是 {@code saveNotice({noticeNo, status})}，而保存端点的入参
 * DTO 里**没有 status**（转写入 DTO 那批去掉的 —— 对的，那条路等于「随便写任何状态」，
 * 一条状态机的边都不用走）。**但没有补上替代的动作**，于是两种失败形态，
 * 都不说真正的原因：
 * <ul>
 *   <li>只发 {@code {noticeNo, status}}（前端的真实请求）→ 400「公告标题必填」
 *       —— 运营根本没碰标题；</li>
 *   <li>补齐全量字段再加 {@code status} → 200，而库里仍是 DRAFT。</li>
 * </ul>
 * 而 C 端只读 {@code PUBLISHED}，所以结果是<b>公告发不出去、C 端公告永远为空</b>。
 *
 * <p>本类走的是**整条链**（运营建 → C 端读不到 → 运营发布 → C 端读得到 → 下线 → 又读不到），
 * 而不是单测某个方法：断掉的正是中间那一环，两端各自看都是好的。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoticePublishReachesConsumerTest extends ApiTestSupport {

    @Test
    @DisplayName("建 → 发布 → C 端看得到 → 下线 → 看不到")
    void draftIsHiddenUntilPublished() {
        String staff = login("ADMIN");
        String title = "对接测试公告 " + (System.nanoTime() % 100000);

        String noticeNo = post("/api/ops/marketing/notices", Map.of(
                "title", title, "content", "内容", "type", "SYSTEM", "pinned", false), staff)
                .okData().path("noticeNo").asText();

        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        String token = post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();

        assertThat(titles(token)).as("刚建出来是草稿，C 端不该看见").doesNotContain(title);

        var published = post("/api/ops/marketing/notices/" + noticeNo + "/publish", Map.of(), staff).okData();
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedBy").asText())
                .as("发布人取自会话，不接受调用方提供").isNotBlank();

        assertThat(titles(token))
                .as("""
                        发布之后 C 端必须读得到。读不到就是这条链断了 ——
                        而两端各自看都正常：运营侧列表里它是「已发布」，C 端只是空列表。""")
                .contains(title);

        post("/api/ops/marketing/notices/" + noticeNo + "/offline", Map.of(), staff).okData();
        assertThat(titles(token)).as("下线后立刻不可见").doesNotContain(title);
    }

    /**
     * 状态**不能**从保存端点写进去 —— 那是这次修掉的那条路。
     * 保存端点重新接受 status 的话，「随便写任何状态」就又回来了。
     */
    @Test
    @DisplayName("保存端点写不动 status（状态只走动作端点）")
    void saveEndpointCannotSetStatus() {
        String staff = login("ADMIN");
        String noticeNo = post("/api/ops/marketing/notices", Map.of(
                "title", "状态写入测试", "content", "内容", "type", "SYSTEM"), staff)
                .okData().path("noticeNo").asText();

        var saved = post("/api/ops/marketing/notices/" + noticeNo, Map.of(
                "title", "状态写入测试", "content", "内容", "type", "SYSTEM",
                "status", "PUBLISHED"), staff).okData();

        assertThat(saved.path("status").asText())
                .as("保存端点收到 status 也不该生效；生效就意味着状态机可以被绕过")
                .isEqualTo("DRAFT");
    }

    private String titles(String consumerToken) {
        StringBuilder sb = new StringBuilder();
        get("/mp/notice", consumerToken).okData().forEach(n -> sb.append(n.path("title").asText()).append('|'));
        return sb.toString();
    }
}
