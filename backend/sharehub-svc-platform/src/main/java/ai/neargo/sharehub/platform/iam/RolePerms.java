package ai.neargo.sharehub.platform.iam;

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
                    "device:command:*", "device:inventory:*", "device:ota:*", "device:vendor:read",
                    "location:poi:read", "location:venue:read", "location:overview:read",
                    "order:order:read", "order:exception:read", "order:exception:handle",
                    "workorder:*", "agent:scope:assign",
                    "report:device:read", "report:location:read",
                    "system:notify_template:read", "system:dict:read",
                    // —— D6d 权限对账补配（2026-09-23）——
                    // 判据：功能权限清单（RBAC SSOT）明确给了本角色 + 端点确实存在，
                    // 而这里漏配 → 该功能对除 ADMIN 外所有人 403，**而界面上按钮是渲染出来的**。
                    "system:notify_log:read", "system:app_version:read",
                    // ⚠️ 这两个码**功能权限清单里没有**（D6d 待确认）：
                    //   · system:market:read —— §13 有「多国家市场 查/改」但 OPS 列为空
                    //   · system:param:read —— 清单里只有 system:dict:read，这是前端另造的码，
                    //     违反「新造权限码必须先补本表再用」。端点 GET /api/platform/sys-params 确实存在。
                    // 暂按前端既有配置保留（删了会让运维看不到系统参数），确认后补进清单或改码。
                    "system:market:read", "system:param:read"),
            "CS", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "device:cabinet:read", "device:slot:read", "device:command:send",
                    "order:order:read", "order:order:export", "order:exception:read", "order:exception:handle",
                    "order:intervene:execute", "order:refund:apply",
                    "user:cuser:read", "user:risk:update", "user:member:read", "user:wallet:read",
                    "workorder:wo:read", "workorder:wo:create", "cs:*",
                    "marketing:coupon:read", "marketing:coupon:issue", "marketing:push:send",
                    // —— D6d 权限对账补配（2026-09-23）——
                    // 判据：功能权限清单（RBAC SSOT）明确给了本角色 + 端点确实存在，
                    // 而这里漏配 → 该功能对除 ADMIN 外所有人 403，**而界面上按钮是渲染出来的**。
                    "marketing:notice:read",
                    "system:notify_log:read", "system:notify_blacklist:read", "system:notify_blacklist:update",
                    "system:problem:read", "system:problem:update"),
            "FINANCE", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "order:order:read", "order:order:export", "order:refund:audit",
                    "pricing:*", "finance:*",
                    "agent:agent:read", "agent:share:config", "agent:settlement:read", "agent:performance:read",
                    "location:venue:read", "location:contract:read", "location:analysis:read",
// D6d：站点概览改判专属码（见 OperationController 的注释）。
                    // 清单第 67 行把 location:overview:read 给了 OPS/FIN/BD/VIEW，
                    // 而后端此前没有任何端点用它 —— 前端拿它渲染入口、后端拿更宽的 poi:read 判访问。
                    "location:overview:read",
                    "user:cuser:read", "user:member:read", "user:wallet:read",
                    "report:*", "org:audit:read",
                    // —— D6d 权限对账补配（2026-09-23）——
                    // 判据：功能权限清单（RBAC SSOT）明确给了本角色 + 端点确实存在，
                    // 而这里漏配 → 该功能对除 ADMIN 外所有人 403，**而界面上按钮是渲染出来的**。
                    "system:bank:read", "system:bank:update", "system:tax:update",
                    "system:biz_rule:update", "system:payment_channel:read"),
            "BD", List.of(
                    "dashboard:overview:read", "location:*", "pricing:rule:read", "pricing:rule:update",
                    "marketing:*", "agent:*",
                    "finance:share_rule:read", "finance:share_record:read", "report:*"),
            "VIEWER", List.of(
                    "dashboard:overview:read", "dashboard:todo:read",
                    "device:cabinet:read", "device:slot:read", "order:order:read",
                    "location:poi:read", "location:venue:read", "location:analysis:read", "location:overview:read",
                    "finance:share_rule:read", "finance:share_record:read", "finance:settlement:read",
                    "finance:withdrawal:read", "agent:agent:read", "report:*"),
            "AGENT", List.of(
                    "dashboard:overview:read", "device:cabinet:read", "device:slot:read",
                    // ⚠️ workorder:wo:read：功能权限清单 §7 未给 AGENT（D6d 待确认）。
                    // **保留**的理由：代理门户有「设备报修」（wo:create），能提报却看不到自己提的工单
                    // 说不通。倾向清单漏标，确认后补进清单。
                    "workorder:wo:create", "workorder:wo:read", "order:order:read",
                    "finance:share_record:read", "finance:withdrawal:apply",
                    "agent:settlement:read", "location:poi:read",
                    // 代理门户要显示「你还不能收款」，判据是有没有可用收款账户 ——
                    // 没有这个码的话 can() 判 false、查询被 disable，那条提示**永远不显示**，
                    // 而代理商会一路提交到审批被拒才知道。**只给读，不给 update**：
                    // 账户一改钱就换个地方进，那个动作留在运营侧（功能权限清单 §6 注）
                    "finance:payout_account:read"));

    public static List<String> of(String role) {
        return MAP.getOrDefault(role, List.of());
    }
}
