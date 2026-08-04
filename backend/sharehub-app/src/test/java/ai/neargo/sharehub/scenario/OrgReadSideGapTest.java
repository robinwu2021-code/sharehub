package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补两个读侧缺口的回归：角色权限回显、审计详情。
 *
 * <p><b>第一条是数据丢失防线</b>：{@code PUT /roles/{no}/permissions} 是覆盖写，
 * 若读侧缺失或读到的不是 {@code iam_role_perm}，前端勾选树空着打开 → 首次保存即抹掉该角色权限。
 * 所以这里断言的是 <b>写进去的能原样读回来</b>（round-trip），而不只是「接口返回 200」。
 *
 * <p>角色选 {@code CUSTOM}：{@code IamSeeder} 灌的唯一 {@code builtin=0} 角色 ——
 * 内置角色写侧会被拒（400），拿它测不到覆盖写。用完把种子权限写回去，避免污染同类用例。
 */
class OrgReadSideGapTest extends ApiTestSupport {

    /** IamSeeder 给 CUSTOM 灌的初始权限，测试结束需还原。 */
    private static final List<String> SEEDED = List.of("dashboard:overview:read", "device:cabinet:read");
    private static final String ROLE_PERMS = "/api/platform/iam/roles/CUSTOM/permissions";

    // —— 角色权限：读得到 + 写完读回来一致 ——

    @Test
    void role_permissions_are_readable_and_match_seed() {
        // 回显的是 iam_role_perm 里的真实行；空数组会让勾选树打开即全空 → 覆盖写抹权限
        assertThat(codes(get(ROLE_PERMS, login("ADMIN")).okData())).containsExactlyInAnyOrderElementsOf(SEEDED);
    }

    @Test
    void role_permissions_round_trip_through_overwrite_write() {
        String admin = login("ADMIN");
        List<String> wanted = List.of("order:order:read", "report:device:read");
        try {
            put(ROLE_PERMS, Map.of("perms", wanted), admin).okData();
            // 覆盖写语义：读回来必须是**恰好**这两个（旧的两个已被删），多一个就说明 delete 没生效
            assertThat(codes(get(ROLE_PERMS, admin).okData())).containsExactlyInAnyOrderElementsOf(wanted);
        } finally {
            put(ROLE_PERMS, Map.of("perms", SEEDED), admin).okData();
            assertThat(codes(get(ROLE_PERMS, admin).okData())).containsExactlyInAnyOrderElementsOf(SEEDED);
        }
    }

    @Test
    void unknown_role_is_bad_request_not_empty_list() {
        // 「角色不存在」与「角色存在但没配权限」必须可区分：前者 400，后者 200 + []
        assertThat(get("/api/platform/iam/roles/NO_SUCH_ROLE/permissions", login("ADMIN")).status).isEqualTo(400);
    }

    @Test
    void role_permissions_require_org_role_read() {
        assertThat(get(ROLE_PERMS, null).status).isEqualTo(401);            // 未认证
        assertThat(get(ROLE_PERMS, login("VIEWER")).status).isEqualTo(403); // VIEWER 无 org:role:read
    }

    @Test
    void writing_role_permissions_requires_update_perm() {
        // 读得到不等于写得了：写侧是 org:role:update，只有 ADMIN(*) 有
        assertThat(put(ROLE_PERMS, Map.of("perms", List.of()), login("FINANCE")).status).isEqualTo(403);
    }

    // —— 审计详情 ——

    @Test
    void audit_detail_matches_the_list_row_and_declares_no_fake_diff() {
        String finance = login("FINANCE");   // FINANCE 有 org:audit:read
        // 分页包体是 neargo PageResult → data.list / data.total（不是 records）
        JsonNode firstRow = get("/api/platform/audit-logs?page=1&size=1", finance).okData().path("list").get(0);
        String id = firstRow.path("id").asText();

        JsonNode d = get("/api/platform/audit-logs/" + id, finance).okData();
        assertThat(d.path("id").asText()).isEqualTo(id);
        assertThat(d.path("actor").asText()).isEqualTo(firstRow.path("actor").asText());
        assertThat(d.path("action").asText()).isEqualTo(firstRow.path("action").asText());
        assertThat(d.path("target").asText()).isEqualTo(firstRow.path("target").asText());
        assertThat(d.path("ip").asText()).isEqualTo(firstRow.path("ip").asText());
        // requestId/userAgent 现有 DDL 无列 → 出空串而非编造；前端渲染「—」由前端兜
        assertThat(d.has("requestId")).isTrue();
        assertThat(d.has("userAgent")).isTrue();
        // changes 必须是数组且为空：没有任何列记录字段级前后值，出假 diff 比没有更糟
        assertThat(d.path("changes").isArray()).isTrue();
        assertThat(d.path("changes")).isEmpty();
    }

    @Test
    void audit_detail_unknown_id_is_bad_request() {
        assertThat(get("/api/platform/audit-logs/A-NOPE", login("FINANCE")).status).isEqualTo(400);
    }

    @Test
    void audit_detail_requires_org_audit_read() {
        String path = "/api/platform/audit-logs/A9000";
        assertThat(get(path, null).status).isEqualTo(401);
        assertThat(get(path, login("VIEWER")).status).isEqualTo(403);   // VIEWER 无 org:audit:read
    }

    private static List<String> codes(JsonNode arr) {
        assertThat(arr.isArray()).as("权限码应为 JSON 数组").isTrue();
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
