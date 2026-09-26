package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值套餐的写入面：归档状态要看得见，金额与归档时间不能混着改。
 *
 * <h2>归档了，但界面上看不出来</h2>
 * {@code usr_recharge_pkg.archived_at} 有列（{@code datetime(3)}，注释「归档时间；null=在用」），
 * 归档端点 {@code /recharge-packages/{no}/archive} 也在，盖时间戳确实写进去了 ——
 * 而 {@code RechargePackageServiceImpl.toVO} <b>硬编一个 null</b>，还配着一句
 * 「DDL 无 archived_at 列 …… 补列前恒为 null，不假造值」。
 *
 * <p>那句注释在写下时是对的，列后来补上了，注释没跟着改。
 * 于是运营点了「归档」，列表刷新回来这一行看上去毫无变化 —— 前端
 * {@code RechargePackage extends Archivable}，它等着这个字段。
 * <b>这类缺陷不报错：功能是好的，只有「看不看得见」坏了。</b>
 * （券模板 {@code CouponTplServiceImpl.toVO} 2026-09-24 栽过一模一样的一次。）
 */
class RechargePackageWriteSurfaceTest extends ApiTestSupport {

    private static String uniq() {
        return "PKG_T" + System.nanoTime();
    }

    private String create(String admin, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("packageNo", uniq());
        m.put("name", name);
        m.put("payAmount", new BigDecimal("100.00"));
        m.put("giftAmount", new BigDecimal("20.00"));
        m.put("currency", "AED");
        m.put("validDays", 365);
        return post("/api/user/recharge-packages", m, admin).okData().path("packageNo").asText();
    }

    /**
     * 「适用市场」此前**存不进去**：读出参从 {@code usr_recharge_pkg_market} 关联表拼 CSV，
     * 而写入 DTO {@code RechargePackageReq} 根本没有这个字段，端点调的是基类 {@code save(实体)}。
     * 运营在必填的「适用市场」勾了国家、保存拿到 200，关联表一行都没写。
     *
     * <p>后果不是「少一列显示」：C 端按用户所在国家筛套餐，
     * <b>没有市场的套餐对每个用户都不出现</b> —— 症状是「新建的套餐 C 端看不到」，
     * 没人会想到是这一格。
     */
    @Test
    @DisplayName("★★ 适用市场存得进去，且取消勾选真的取消（全量重写不是只增）")
    void marketsAreSaved() {
        String admin = login("ADMIN");
        Map<String, Object> m = new HashMap<>();
        m.put("packageNo", uniq());
        m.put("name", "市场测试包");
        m.put("payAmount", new BigDecimal("50.00"));
        m.put("giftAmount", new BigDecimal("5.00"));
        m.put("currency", "AED");
        m.put("validDays", 180);
        m.put("markets", "AE,SA");
        String no = post("/api/user/recharge-packages", m, admin).okData().path("packageNo").asText();

        assertThat(row(admin, no).path("markets").asText())
                .as("建单就该带上市场 —— 此前这里是空的，而 HTTP 仍然 200")
                .isEqualTo("AE,SA");

        // 改成只留 AE：取消勾选必须真的取消，否则「已取消的市场仍买得到」
        m.put("markets", "AE");
        post("/api/user/recharge-packages/" + no, m, admin).okData();
        assertThat(row(admin, no).path("markets").asText())
                .as("全量重写：只增不删的话 SA 会留在关联表里")
                .isEqualTo("AE");
    }

    private JsonNode row(String admin, String no) {
        return findInPages("/api/user/recharge-packages?showArchived=true", "packageNo", no, admin);
    }

    @Test
    @DisplayName("★★ 归档之后 archivedAt 要有值——否则运营点完看不出有没有生效")
    void archiving_is_visible_in_the_list() {
        String admin = login("ADMIN");
        String no = create(admin, "归档可见性探针");

        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("前提：刚建的套餐未归档").isFalse();

        post("/api/user/recharge-packages/" + no + "/archive", Map.of(), admin).okData();

        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("归档端点盖了时间戳，出参就该带上——前端 RechargePackage extends Archivable 等着它")
                .isTrue();

        post("/api/user/recharge-packages/" + no + "/unarchive", Map.of(), admin).okData();
        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("取消归档后回到 null").isFalse();
    }

    @Test
    @DisplayName("保存端点不能顺手改归档时间——归档有专门入口")
    void archived_at_cannot_be_set_through_save() {
        String admin = login("ADMIN");
        String no = create(admin, "归档时间探针");

        Map<String, Object> evil = new HashMap<>();
        evil.put("name", "改个名字而已");
        evil.put("archivedAt", "2020-01-01 00:00:00");
        post("/api/user/recharge-packages/" + no, evil, admin);

        assertThat(row(admin, no).hasNonNull("archivedAt"))
                .as("归档只能走 /archive；保存端点塞 archivedAt 等于绕过归档语义")
                .isFalse();
    }

    @Test
    @DisplayName("金额仍可正常编辑——白名单不该把正事一起挡掉")
    void amounts_are_still_editable() {
        String admin = login("ADMIN");
        String no = create(admin, "金额编辑探针");

        Map<String, Object> m = new HashMap<>();
        m.put("payAmount", new BigDecimal("200.00"));
        m.put("giftAmount", new BigDecimal("50.00"));
        post("/api/user/recharge-packages/" + no, m, admin).okData();

        JsonNode r = row(admin, no);
        assertThat(new BigDecimal(r.path("payAmount").asText())).isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(r.path("giftAmount").asText())).isEqualByComparingTo("50.00");
    }
}
