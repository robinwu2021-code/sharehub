package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **四个「按钮在、接口不在」的写入口**（《前后端对齐缺口》A 类清零）。
 *
 * <p>裂变规则 · 角色 · 触达拉黑修改 · 钱包手工调账 —— 前端都已按目标形状写好请求，
 * 后端一行没有，于是这四处的「新增/编辑」在 {@code USE_MOCK=0} 下**全是 404**。
 *
 * <p>用例盯的不是「能存下来」，而是这几处**存下来了却是错的**：
 * 状态存进去显示成反的 · 改内置角色把权限判定指向不存在的角色 ·
 * 改掉脱敏后的拉黑对象等于换了个人 · 调了余额却不记流水。
 */
class MissingWriteEndpointsTest extends ApiTestSupport {

    // ——— 裂变规则 ———

    private Map<String, Object> rule(String name, String status) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("rewardTo", "BOTH");
        m.put("rewardAmount", 10);
        m.put("currency", "AED");
        m.put("trigger", "FIRST_ORDER");
        m.put("maxPerInviter", 5);
        m.put("status", status);
        return m;
    }

    @Test
    void referral_rule_status_round_trips() {
        // 出参把 ENABLED 映射成 ACTIVE。入参不映射回去的话，存 "ACTIVE" 进库，
        // 列表会把它显示成「已停用」—— 存了、看着却是反的，而没有任何报错。
        String admin = login("ADMIN");
        JsonNode c = post("/api/user/referral-rules", rule("裂变规则往返", "ACTIVE"), admin).okData();
        assertThat(c.path("ruleNo").asText()).isNotBlank();
        assertThat(c.path("status").asText()).as("存 ACTIVE 读回来还要是 ACTIVE").isEqualTo("ACTIVE");

        JsonNode d = post("/api/user/referral-rules/" + c.path("ruleNo").asText(),
                rule("裂变规则往返", "DISABLED"), admin).okData();
        assertThat(d.path("status").asText()).isEqualTo("DISABLED");
    }

    @Test
    void referral_rule_rejects_unknown_enums() {
        // 脏枚举进库后发奖时谁都匹配不上，而那是**静默不发奖**：没人会收到投诉。
        String admin = login("ADMIN");
        Map<String, Object> bad = rule("非法奖励对象", "ACTIVE");
        bad.put("rewardTo", "EVERYONE");
        assertThat(post("/api/user/referral-rules", bad, admin).status).isEqualTo(400);

        Map<String, Object> bad2 = rule("非法触发时机", "ACTIVE");
        bad2.put("trigger", "WHENEVER");
        assertThat(post("/api/user/referral-rules", bad2, admin).status).isEqualTo(400);
    }

    // ——— 角色 ———

    @Test
    void role_can_be_created_and_renamed() {
        String admin = login("ADMIN");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "TESTROLE" + System.nanoTime() % 100000);
        body.put("name", "测试角色");
        JsonNode r = post("/api/platform/roles", body, admin).okData();
        String roleNo = r.path("roleNo").asText();
        assertThat(roleNo).isNotBlank();

        Map<String, Object> rename = new HashMap<>();
        rename.put("name", "测试角色改名");
        assertThat(post("/api/platform/roles/" + roleNo, rename, admin).okData().path("name").asText())
                .isEqualTo("测试角色改名");
    }

    @Test
    void builtin_role_cannot_be_modified() {
        // RolePerms 按 code 认内置角色。改掉等于把一整套权限判定指向一个不存在的角色 ——
        // 页面不报错，只是那个角色的人忽然什么都看不见。
        String admin = login("ADMIN");
        JsonNode roles = get("/api/platform/roles", admin).okData();
        String builtinNo = null;
        for (JsonNode r : roles) {
            if (r.path("builtin").asBoolean()) { builtinNo = r.path("roleNo").asText(); break; }
        }
        assertThat(builtinNo).as("前提：种子里有内置角色").isNotNull();

        assertThat(post("/api/platform/roles/" + builtinNo, Map.of("name", "偷偷改名"), admin).status)
                .isEqualTo(400);
    }

    @Test
    void duplicate_role_code_is_refused() {
        // 编码是权限判定的连接键，重了就有两套权限抢同一个 code。
        String admin = login("ADMIN");
        // 全精度 nanoTime，不取模：test_sharehub 是**有状态共享库**，每跑一次留一个
        // DUP 角色（现已积累 50+）。取模到 5 位后空间只有 10 万，而 nanoTime 的低位
        // 在很多平台上粒度很粗（常是千的倍数），撞码概率远高于 1/100000 ——
        // 撞上时红的是**第一个** post（400 "编码已存在"），看起来像「新建角色坏了」。
        String code = "DUP" + System.nanoTime();
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "重码测试");
        post("/api/platform/roles", body, admin).okData();
        assertThat(post("/api/platform/roles", body, admin).status).isEqualTo(400);
    }

    // ——— 触达拉黑 ———

    @Test
    void blacklist_entry_can_be_edited_but_target_is_frozen() {
        String admin = login("ADMIN");
        Map<String, Object> block = new HashMap<>();
        block.put("target", "+971500001234");
        block.put("channel", "SMS");
        block.put("reason", "MANUAL");
        JsonNode b = post("/api/platform/notify-blacklist", block, admin).okData();
        String no = b.path("blockNo").asText();
        String maskedTarget = b.path("target").asText();

        JsonNode edited = post("/api/platform/notify-blacklist/" + no,
                Map.of("reason", "COMPLAINT", "target", "+971509999999"), admin).okData();
        assertThat(edited.path("reason").asText()).as("原因可改").isEqualTo("COMPLAINT");
        // target 是脱敏存的：改掉等于换了一个人被拉黑，而从掩码上根本看不出换没换
        assertThat(edited.path("target").asText()).as("拉黑对象冻结").isEqualTo(maskedTarget);
    }

    @Test
    void released_blacklist_entry_cannot_be_edited() {
        // 已解除的是历史记录：解除动作是对当时那份内容做的，事后改内容就对不上了。
        String admin = login("ADMIN");
        Map<String, Object> block = new HashMap<>();
        block.put("target", "+971500005678");
        block.put("channel", "SMS");
        String no = post("/api/platform/notify-blacklist", block, admin).okData().path("blockNo").asText();
        post("/api/platform/notify-blacklist/" + no + "/release", Map.of(), admin).okData();

        assertThat(post("/api/platform/notify-blacklist/" + no, Map.of("reason", "X"), admin).status)
                .isEqualTo(400);
    }

    // ——— 钱包手工调账 ———

    @Test
    void wallet_adjust_requires_a_user() {
        // 给一个不存在的用户开钱包，那笔钱永远没人认领，却会进所有统计。
        String admin = login("ADMIN");
        assertThat(post("/api/user/wallets", Map.of("balance", 100), admin).status)
                .as("没指定用户必须拒").isEqualTo(400);
        assertThat(post("/api/user/wallets/U-NOT-EXIST", Map.of("balance", 100), admin).status)
                .as("用户不存在必须拒").isEqualTo(400);
    }

    @Test
    void wallet_adjust_accepts_the_shape_the_page_actually_sends() {
        // 前端的编辑表单把**整行**回传（含 nickname / orderCount 这些算出来的字段）。
        // 入参若复用读模型 WalletRow，那些 primitive 聚合字段一旦缺失或为 null，
        // Jackson 直接抛 → 500，而页面上只看到「服务器错误」。
        // 这里用真实形状打一遍：必须干净地走到业务校验（400），而不是反序列化就炸（500）。
        String admin = login("ADMIN");
        Map<String, Object> full = new HashMap<>();
        full.put("userNo", "U-NOT-EXIST");
        full.put("nickname", "某用户");
        full.put("balance", 100);
        full.put("bonus", 5);
        full.put("currency", "AED");
        full.put("updatedAt", "2026-09-24 08:00:00");
        full.put("orderCount", 3);
        full.put("orderAmount", 120);
        full.put("rechargeCount", 1);
        full.put("rechargeAmount", 50);
        assertThat(post("/api/user/wallets", full, admin).status)
                .as("整行形状要能反序列化，错在业务而不是解析").isEqualTo(400);
    }
}
