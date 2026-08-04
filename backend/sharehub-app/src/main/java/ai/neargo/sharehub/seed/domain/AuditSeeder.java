package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.dto.Dto.AuditEntry;
import ai.neargo.sharehub.platform.org.entity.IamAuditLog;
import ai.neargo.sharehub.platform.org.mapper.IamAuditLogMapper;
import ai.neargo.sharehub.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 审计域首次落库：{@code iam_audit_log} 空时灌内存种子。幂等。
 *
 * <p>为什么需要它：审计切面（{@code @OperateLog → append}）尚未落地，表天然为空；
 * {@code PlatformController} 的审计列表已随骨架退役改读真表 —— 没有种子，
 * 演示环境的审计页就是白板，round-trip 场景测试也无从跑起。
 * 种子行的 {@code target} 拆进 {@code target_no}（种子只有单列展示值，不硬编目标类型）。
 */
@Component
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(6)
public class AuditSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final IamAuditLogMapper mapper;

    public AuditSeeder(SeedData seed, IamAuditLogMapper mapper) {
        this.seed = seed;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (AuditEntry a : seed.audits()) {
            IamAuditLog e = new IamAuditLog();
            e.setTenantId("MAIN");
            e.setActor(a.actor());
            e.setActorName(a.actor());
            e.setAction(a.action());
            // target_no / detail 列是 JSON 类型（V13 对账产物，带 json_valid CHECK）——
            // 必须写 JSON 字面量；targetType 留 null：种子只有单列展示值，不硬编目标类型
            e.setTargetNo(ai.neargo.sharehub.common.Json.write(a.target()));
            e.setDetail(ai.neargo.sharehub.common.Json.write(a.detail()));
            e.setIp(a.ip());
            e.setCreatedAt(parse(a.createdAt()));
            e.setCreatedBy(a.actor());
            mapper.insert(e);
        }
    }

    private static LocalDateTime parse(String ts) {
        if (ts == null || ts.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(ts.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
