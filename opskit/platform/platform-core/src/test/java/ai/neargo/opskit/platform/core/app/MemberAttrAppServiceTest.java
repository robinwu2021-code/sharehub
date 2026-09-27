package ai.neargo.opskit.platform.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.commons.testing.TestDatabase;
import ai.neargo.opskit.platform.api.directory.MemberAttrDef;
import ai.neargo.opskit.platform.core.support.DirectoryEnv;
import ai.neargo.opskit.platform.store.dao.MemberAttrDao;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 21/07 O1-12：属性值显示名由租户配置；空间覆盖模板；未定义的只回 value。 */
class MemberAttrAppServiceTest {

    @ParameterizedTest
    @MethodSource("ai.neargo.opskit.commons.testing.TestDatabases#all")
    void rendersConfiguredLabelsAndFallsBackToRawValues(TestDatabase db) {
        DirectoryEnv e = new DirectoryEnv(db);
        MemberAttrAppService svc = new MemberAttrAppService(new MemberAttrDao(e.f.jdbc, db.dialect()), e.f.clock, e.f.tx, e.audit);
        svc.upsert(e.system(), WorkspaceId.TEMPLATE, new MemberAttrDef("partyType", Map.of("zh", "身份"),
                Map.of("AGENT", Map.of("zh", "代理", "en", "Agent"), "STAFF", Map.of("zh", "员工"))));
        svc.upsert(e.system(), e.workspace, new MemberAttrDef("partyType", Map.of("zh", "身份"),
                Map.of("AGENT", Map.of("zh", "合作商", "en", "Partner"))));

        Map<String, Map<String, Object>> r = svc.render(e.tenant, Map.of("partyType", "AGENT", "level", 3), "zh");

        assertThat(r.get("partyType")).containsEntry("value", "AGENT").containsEntry("name", "合作商");
        assertThat(r.get("level")).as("未定义显示名：只有 value").containsOnlyKeys("value");
        assertThat(svc.render(e.tenant, Map.of("partyType", "AGENT"), "ar").get("partyType")).containsEntry("name", "Partner");
    }
}
