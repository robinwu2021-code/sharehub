package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公告的写入面。公告是**推给 C 端全体用户**的东西，署名与上下线都得站得住。
 *
 * <h2>归档了但界面看不出来 —— 第三次撞见同一个形状</h2>
 * {@code mkt_notice.archived_at} 有列（注释「归档时间；null=在用」），
 * {@code /notices/{no}/archive|unarchive} 也在，而 {@code NoticeServiceImpl.toVO}
 * 硬编一个 null，配着一句「本表无归档列，恒 null」。
 * 那句在写下时是对的，列后来补上了，注释没跟着改。
 * 前端 {@code Notice extends Archivable} 一直等着这个字段。
 *
 * <p>前两次分别是券模板（2026-09-24）与充值套餐（2026-09-25）。
 * <b>同一种错连出三次，说明它不是手误</b>：补列的人只改 DDL 不看读侧，
 * 而读侧那句注释长得像结论。已在 {@code OrphanNotNullColumnTest} 之外
 * 另记一笔到备忘里。
 *
 * <h2>发布人可以冒名</h2>
 * {@code published_by} 注释是 {@code employee_no} —— 谁发的这条公告。
 * 实体直接当请求体、service 也没有 {@code beforeUpdate}，于是请求里塞一个别人的工号就行。
 * 公告是给全体用户看的，署名错了不是小事，而且它<b>不报错</b>。
 */
class NoticeWriteSurfaceTest extends ApiTestSupport {

    private static String uniq() {
        return "NT_T" + System.nanoTime();
    }

    private String create(String admin, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("noticeNo", uniq());
        m.put("title", title);
        m.put("titleEn", "probe");
        m.put("content", "探针正文");
        m.put("type", "SYSTEM");
        return post("/api/ops/marketing/notices", m, admin).okData().path("noticeNo").asText();
    }

    private JsonNode row(String admin, String no) {
        return findInPages("/api/ops/marketing/notices?showArchived=true", "noticeNo", no, admin);
    }

    @Test
    @DisplayName("★★ 归档之后 archivedAt 要有值——否则运营点完看不出有没有生效")
    void archiving_is_visible_in_the_list() {
        String admin = login("ADMIN");
        String no = create(admin, "归档可见性探针");
        assertThat(row(admin, no).hasNonNull("archivedAt")).as("前提：刚建的未归档").isFalse();

        post("/api/ops/marketing/notices/" + no + "/archive", Map.of(), admin).okData();

        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("归档端点盖了时间戳，出参就该带上——前端 Notice extends Archivable 等着它")
                .isTrue();

        post("/api/ops/marketing/notices/" + no + "/unarchive", Map.of(), admin).okData();
        assertThat(row(admin, no).hasNonNull("archivedAt")).as("取消归档后回 null").isFalse();
    }

    @Test
    @DisplayName("★★ 发布人不接受客户端传——公告是给全体用户看的，署名不能冒名")
    void published_by_cannot_be_forged() {
        String admin = login("ADMIN");

        Map<String, Object> evil = new HashMap<>();
        evil.put("noticeNo", uniq());
        evil.put("title", "冒名探针");
        evil.put("titleEn", "probe");
        evil.put("content", "正文");
        evil.put("type", "SYSTEM");
        evil.put("publishedBy", "EMP_SOMEONE_ELSE");
        String no = post("/api/ops/marketing/notices", evil, admin).okData().path("noticeNo").asText();

        assertThat(row(admin, no).path("publishedBy").asText(""))
                .as("发布人由服务端按当前登录人回填，传进来的一律不算")
                .isNotEqualTo("EMP_SOMEONE_ELSE");
    }

    @Test
    @DisplayName("保存端点不能顺手改归档时间——归档有专门入口")
    void archived_at_cannot_be_set_through_save() {
        String admin = login("ADMIN");
        String no = create(admin, "归档时间探针");

        Map<String, Object> evil = new HashMap<>();
        evil.put("title", "改个标题而已");
        evil.put("archivedAt", "2020-01-01 00:00:00");
        post("/api/ops/marketing/notices/" + no, evil, admin);

        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("归档只能走 /archive；保存端点塞 archivedAt 等于绕过归档语义")
                .isFalse();
    }

    @Test
    @DisplayName("正文与三语仍可正常编辑——白名单不该把正事挡掉")
    void content_is_still_editable() {
        String admin = login("ADMIN");
        String no = create(admin, "编辑探针");

        Map<String, Object> m = new HashMap<>();
        m.put("title", "改过的标题");
        m.put("titleEn", "Updated");
        m.put("content", "改过的正文");
        post("/api/ops/marketing/notices/" + no, m, admin).okData();

        JsonNode r = row(admin, no);
        assertThat(r.path("title").asText()).isEqualTo("改过的标题");
        assertThat(r.path("titleEn").asText()).isEqualTo("Updated");
    }
}
