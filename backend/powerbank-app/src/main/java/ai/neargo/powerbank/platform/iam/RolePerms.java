package ai.neargo.powerbank.platform.iam;

import java.util.List;
import java.util.Map;

/**
 * 服务端角色→权限码映射（与 ops-web {@code lib/permissions.ts} 同源，权限清单见
 * docs/requirements/功能权限清单.md）。支持通配 {@code *} / {@code device:*}。
 *
 * <p>MVP 硬编码；后续换 {@code iam_role_permission} 落库 + 缓存。是**授权唯一权威**——
 * StaffTokenAuthFilter 据此写 authorities，前端 X-Roles 不参与鉴权。
 */
public final class RolePerms {

    private RolePerms() {
    }

    public static final Map<String, List<String>> MAP = Map.of(
            "ADMIN", List.of("*"),
            "OPS", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "device:cabinet:*", "device:slot:read", "device:powerbank:*",
                    "device:command:*", "device:inventory:*", "device:ota:read", "device:vendor:read",
                    "location:poi:read", "location:venue:read",
                    "order:order:read", "order:exception:read", "order:exception:handle",
                    "workorder:*", "agent:scope:assign",
                    "report:device:read", "report:location:read",
                    "system:notify_template:read", "system:dict:read"),
            "CS", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "device:cabinet:read", "device:slot:read", "device:command:send",
                    "order:order:read", "order:order:export", "order:exception:read", "order:exception:handle",
                    "order:intervene:execute", "order:refund:apply",
                    "user:cuser:read", "user:risk:update", "user:member:read", "user:wallet:read",
                    "workorder:wo:read", "workorder:wo:create", "cs:*",
                    "marketing:coupon:read", "marketing:coupon:issue", "marketing:push:send"),
            "FINANCE", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "order:order:read", "order:order:export", "order:refund:audit",
                    "pricing:*", "finance:*",
                    "agent:agent:read", "agent:share:config", "agent:settlement:read", "agent:performance:read",
                    "location:venue:read", "location:contract:read", "location:analysis:read",
                    "user:cuser:read", "user:member:read", "user:wallet:read",
                    "report:*", "org:audit:read"),
            "BD", List.of(
                    "dashboard:overview:read", "location:*", "pricing:rule:read", "pricing:rule:update",
                    "marketing:*", "agent:*",
                    "finance:share_rule:read", "finance:share_record:read", "report:*"),
            "VIEWER", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "device:cabinet:read", "device:slot:read", "order:order:read",
                    "location:poi:read", "location:venue:read", "location:analysis:read",
                    "finance:share_rule:read", "finance:share_record:read", "finance:settlement:read",
                    "finance:withdrawal:read", "report:*"),
            "AGENT", List.of(
                    "dashboard:overview:read", "device:cabinet:read", "device:slot:read",
                    "workorder:wo:create", "order:order:read",
                    "finance:share_record:read", "finance:withdrawal:apply",
                    "agent:settlement:read", "location:poi:read"));

    public static List<String> of(String role) {
        return MAP.getOrDefault(role, List.of());
    }
}
