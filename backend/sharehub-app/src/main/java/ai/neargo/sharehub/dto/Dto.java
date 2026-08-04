package ai.neargo.sharehub.dto;

import java.util.List;

/**
 * 运营端领域 DTO 集合（记录类），字段镜像 ops-web {@code lib/types.ts}。
 * 后端就绪后由 openapi 生成契约替换，这里是骨架种子的出参形状。
 *
 * <p>集中为嵌套 record，减少文件散落；Jackson 按 record 组件名序列化，天然 camelCase。
 */
public final class Dto {

    private Dto() {
    }

    // —— 设备（ops 域）——



    // —— 订单（trade 域）——


    // —— 工单（ops 域）——

    // —— 场所：场地方 → 站点 → 点位 → 合同（ADR-013）——




    // —— 财务：分润 / 结算 / 提现 / 分录（trade 域）——
    public record ShareRule(String ruleNo, String dimension, String payeeName, String mode,
                           double rate, int priority) {
    }

    public record Settlement(String settleNo, String payeeType, String payeeName, String period,
                            double totalAmount, String currency, String status) {
    }

    public record Withdrawal(String withdrawNo, String payeeName, double amount, String currency,
                            String status, String appliedAt) {
    }

    public record LedgerEntry(String entryNo, String voucherNo, String orderNo, String account,
                             String direction, double amount, String currency, String summary,
                             String createdAt) {
    }

    // —— 供应商接入（gw 域）——
    public record Vendor(String vendorCode, String name, String accessMode, String status,
                        String apiBase, int deviceCount) {
    }

    // —— C 端用户 / 营销（user 域）——
    public record CUser(String cUserNo, String nickname, String phone, int creditScore,
                       boolean blacklisted, int orders, String registeredAt) {
    }

    public record Coupon(String couponNo, String name, String type, double value, double threshold,
                        int stock, int issued, String status) {
    }

    // —— 计费模板（trade 域）——
    public record PricePlan(String planNo, String name, int freeMinutes, int unitMinutes, double unitPrice,
                           double capDaily, double capTotal, String currency, String scope, String status) {
    }

    // —— 代理商（agt 域，ADR-012）——

    // —— 员工 / 角色 / 审计（platform 域）——
    public record Employee(String employeeNo, String name, String phone, String deptName,
                          String roleName, String status) {
    }

    public record RoleRow(String roleNo, String code, String name, int permCount, int memberCount,
                         boolean builtin, String dataScope) {
    }

    public record AuditEntry(String id, String actor, String action, String target, String detail,
                            String ip, String createdAt) {
    }

    // —— 工作台 ——
    public record TrendPoint(String day, int gmv, int orders) {
    }

    public record DashboardStats(int gmvToday, int ordersToday, int activeCabinets, double onlineRate,
                                int openWorkOrders, String currency, List<TrendPoint> trend) {
    }

    // —— 动作返回 ——
}
