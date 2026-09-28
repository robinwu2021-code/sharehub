package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort;
import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort.AgentAccount;
import ai.neargo.sharehub.api.platform.port.DirectorySnapshotPort.Snapshot;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 把 ShareHub 的人推进 OpsKit 目录（M5，方案 23 §四-7）。此前 OpsKit 里只有「登录过的人」（JIT）——
 * 没打开过 /flow 的员工与代理<b>派不了单、当不了会签人</b>（2026-09-28 生产 0 个成员）。
 *
 * <h3>批次怎么拼</h3>
 * <ul>
 *   <li><b>成员</b>：员工按工号、代理按人（principal_no）—— 与内省的 sub 同一口径，登录后 JIT 认得是同一个人；
 *       属性 {@code partyType}（STAFF / AGENT，工作台「受理方」列按它显示）；代理带 {@code agent:<号>} 范围（多个账户即多个）。</li>
 *   <li><b>组</b>：每个代理一个 TEAM 组 {@code agent-<代理号>}，成员是它名下的人 —— 派单能「派给某代理」。</li>
 *   <li><b>REPLACE</b>：对本批出现的组，成员名单以本批为准（有人离开代理即移出组）。
 *       本批没出现的成员 / 组不动 —— 离职员工不会被这里删掉，由 OpsKit 侧另行停用。</li>
 * </ul>
 * 每天凌晨全量一次；上线后或人员有大变动时可手动触发 {@code POST /internal/job/opskit-directory-sync/trigger}。
 */
@Component
public class OpsKitDirectorySyncJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OpsKitDirectorySyncJob.class);
    static final String NAME = "opskit-directory-sync";

    private final OpsKitClient client;
    private final DirectorySnapshotPort directory;

    public OpsKitDirectorySyncJob(OpsKitClient client, DirectorySnapshotPort directory) {
        this.client = client;
        this.directory = directory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        if (!client.configured()) return JobResult.skipped("OpsKit 未配置，跳过目录同步");
        Snapshot s = directory.snapshot();
        if (s.staff().isEmpty() && s.agentAccounts().isEmpty()) return JobResult.skipped("没有可同步的人员");
        client.putMemberAttribute("partyType", partyTypeDefinition());
        Map<String, Object> r = client.syncDirectory(batch(s));
        log.info("OpsKit 目录同步完成 员工={} 代理账户={} 应答={}", s.staff().size(), s.agentAccounts().size(), r);
        return JobResult.success("staff=" + s.staff().size() + " agentAccounts=" + s.agentAccounts().size());
    }

    /** 同步批次（与 OpsKit SyncBatch 同形）。包级可见：测试直接断言拼出来的形状 */
    static Map<String, Object> batch(Snapshot s) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (var e : s.staff()) {
            members.add(member(e.employeeNo(), e.name() == null || e.name().isBlank() ? e.employeeNo() : e.name(),
                    Map.of("partyType", "STAFF"), List.of()));
        }
        // 代理按人聚合：一个人多个代理账户 → 一个成员、多个范围标签；显示名取主账户
        Map<String, List<AgentAccount>> byPerson = new TreeMap<>();
        for (var a : s.agentAccounts()) byPerson.computeIfAbsent(a.principalNo(), k -> new ArrayList<>()).add(a);
        Map<String, Set<String>> memberships = new TreeMap<>();
        Map<String, Map<String, Object>> groups = new TreeMap<>();
        for (var entry : byPerson.entrySet()) {
            List<AgentAccount> accts = entry.getValue();
            AgentAccount main = accts.stream().filter(AgentAccount::primary).findFirst().orElse(accts.get(0));
            Set<String> scopes = new LinkedHashSet<>();
            for (var a : accts) {
                scopes.add("agent:" + a.agentNo());
                String code = "agent-" + a.agentNo();
                memberships.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(entry.getKey());
                groups.computeIfAbsent(code, k -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("code", code);
                    g.put("name", a.agentName() == null ? a.agentNo() : a.agentName());
                    g.put("kind", "TEAM");
                    return g;
                });
            }
            members.add(member(entry.getKey(), main.displayName() == null ? entry.getKey() : main.displayName(),
                    Map.of("partyType", "AGENT", "agentNo", main.agentNo()), List.copyOf(scopes)));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("mode", "REPLACE");
        b.put("members", members);
        b.put("groups", List.copyOf(groups.values()));
        Map<String, Object> ms = new LinkedHashMap<>();
        memberships.forEach((k, v) -> ms.put(k, List.copyOf(v)));
        b.put("memberships", ms);
        b.put("roleBindings", List.of());
        return b;
    }

    private static Map<String, Object> member(String ref, String name, Map<String, Object> attrs, List<String> scopes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalRef", ref);
        m.put("displayName", name);
        m.put("attrs", attrs);
        m.put("scopes", scopes);
        return m;
    }

    private static Map<String, Object> partyTypeDefinition() {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("STAFF", Map.of("zh", "员工", "en", "Staff", "ar", "موظف"));
        labels.put("AGENT", Map.of("zh", "代理", "en", "Agent", "ar", "وكيل"));
        return Map.of("name", Map.of("zh", "受理方类型", "en", "Party type", "ar", "نوع الطرف"), "valueLabels", labels);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration opskitDirectorySyncDeclaration() {
            return JobDeclaration.of(NAME, "OpsKit 目录同步（员工 · 代理 · 代理组）", "0 15 3 * * *")
                    .ownerModule("integration.opskit").timeoutSec(120).lockAtMostSec(300).build();
        }
    }
}
