package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开放平台应用的密钥只能由 {@code reset-secret} 换，不能从编辑接口写。
 *
 * <p>2026-09-24 之前**能写**：那两个端点收的是实体 {@code OpenapiApp}（带 appSecretHash），
 * 而 beforeUpdate 的保护是「{@code if (e.getAppSecretHash() == null) 才取库里的}」——
 * 只在客户端**不传**时生效。注释写的是「更新请求不带它时保留原值」，
 * 作者的意图是「别被清空」，不是「不许被设置」。
 *
 * <p>于是带上 {@code appSecretHash} 调编辑接口，就把该应用的密钥换成了自己算的哈希，
 * 等于拿到它的全部 API 权限 —— 而这个端点只需要 {@code system:openapi:update}。
 * 改收 {@code OpenApiAppReq} 白名单后，这个键根本不在入参里。
 */
class OpenApiAppSecretTest extends ApiTestSupport {

    @Test
    @DisplayName("★★★ 带 appSecretHash 调编辑接口 —— 密钥不许被改，重置时间也不许动")
    void editing_an_app_cannot_overwrite_its_secret() {
        String admin = login("ADMIN");

        // appKey 上有唯一键，而**测试库是有状态的共享库**（跨次运行不重置）——
        // 用固定值第二次跑就撞 Duplicate entry。第一版就是这么写的，
        // 于是"修复前会失败"那次实验拿到的是撞键错误，不是真的断言失败。
        String appKey = "ak_probe_" + System.nanoTime();

        Map<String, Object> create = new HashMap<>();
        create.put("name", "[密钥测试] 应用");
        create.put("appKey", appKey);
        create.put("rateLimit", 100);
        JsonNode app = post("/api/platform/openapi-apps", create, admin).okData();
        String appNo = app.path("appNo").asText();

        // 先重置一次，拿到「正常路径」写出来的掩码与时间作为基准
        JsonNode reset = post("/api/platform/openapi-apps/" + appNo + "/reset-secret", Map.of(), admin).okData();
        String maskedBefore = reset.path("appSecretMasked").asText();
        String resetAtBefore = reset.path("secretResetAt").asText();
        assertThat(maskedBefore).as("重置后应当有密钥").isNotBlank();

        // 编辑接口带上密钥哈希与重置时间 —— 都不该生效
        Map<String, Object> poisoned = new HashMap<>();
        poisoned.put("name", "[密钥测试] 改个名");
        poisoned.put("appKey", appKey);
        poisoned.put("rateLimit", 200);
        poisoned.put("appSecretHash", "deadbeef".repeat(8));   // 攻击者自己算的哈希
        poisoned.put("secretResetAt", "2000-01-01T00:00:00");
        JsonNode after = post("/api/platform/openapi-apps/" + appNo, poisoned, admin).okData();

        assertThat(after.path("secretResetAt").asText())
                .as("重置时间被编辑接口改了 —— 密钥的审计痕迹就不可信了")
                .isEqualTo(resetAtBefore);
        assertThat(after.path("rateLimit").asInt())
                .as("正常字段仍应改得动（别把白名单收得过窄）")
                .isEqualTo(200);

        // 掩码只表达「有/无」，改不改得动要看重置时间；这里再确认密钥仍在
        assertThat(after.path("appSecretMasked").asText())
                .as("密钥应当还在")
                .isEqualTo(maskedBefore);
    }
}
