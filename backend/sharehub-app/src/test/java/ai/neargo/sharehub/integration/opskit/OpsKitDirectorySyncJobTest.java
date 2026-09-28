package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort.AgentAccount;
import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort.Snapshot;
import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort.Staff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 目录同步批次的形状（与 OpsKit SyncBatch 对齐）。编号口径错了，同一个人在 OpsKit 里会变成两个成员 */
class OpsKitDirectorySyncJobTest {

    @Test
    @DisplayName("★ 员工按工号、代理按人：一人多账户 = 一个成员 + 多个范围标签 + 进多个代理组；显示名取主账户；REPLACE")
    @SuppressWarnings("unchecked")
    void batchShape() {
        Snapshot s = new Snapshot(
                List.of(new Staff("E001", "Fatima", "FINANCE"), new Staff("E002", " ", "OPS")),
                List.of(new AgentAccount("P9", "AG001", "Alpha", "Omar @Alpha", false),
                        new AgentAccount("P9", "AG002", "Beta", "Omar @Beta", true),
                        new AgentAccount("P7", "AG001", "Alpha", "Sara", true)));
        Map<String, Object> b = OpsKitDirectorySyncJob.batch(s);
        assertThat(b.get("mode")).isEqualTo("REPLACE");

        List<Map<String, Object>> members = (List<Map<String, Object>>) b.get("members");
        assertThat(members).extracting(m -> m.get("externalRef")).containsExactly("E001", "E002", "P7", "P9");
        assertThat(members.get(0)).containsEntry("displayName", "Fatima").containsEntry("attrs", Map.of("partyType", "STAFF"));
        assertThat(members.get(1).get("displayName")).as("没有姓名退回工号").isEqualTo("E002");
        Map<String, Object> omar = members.get(3);
        assertThat(omar.get("displayName")).as("显示名取主账户").isEqualTo("Omar @Beta");
        assertThat((List<String>) omar.get("scopes")).containsExactly("agent:AG001", "agent:AG002");
        assertThat((Map<String, Object>) omar.get("attrs")).containsEntry("partyType", "AGENT").containsEntry("agentNo", "AG002");

        Map<String, List<String>> ms = (Map<String, List<String>>) b.get("memberships");
        assertThat(ms.get("agent-AG001")).containsExactlyInAnyOrder("P7", "P9");
        assertThat(ms.get("agent-AG002")).containsExactly("P9");
        assertThat((List<Map<String, Object>>) b.get("groups")).extracting(g -> g.get("code") + "/" + g.get("kind"))
                .containsExactly("agent-AG001/TEAM", "agent-AG002/TEAM");
    }
}
