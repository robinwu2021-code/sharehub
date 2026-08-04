package ai.neargo.sharehub.trade.dto;

import ai.neargo.sharehub.trade.dto.TradeLegacyDtos.RentOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单人工干预的入参 / 出参 / <b>状态机</b>。字段镜像 {@code ops-web/lib/types/order.ts}。
 *
 * <p>状态机在这里再写一遍，而不是「信任前端已经校验过」—— 前端的 {@code ORDER_INTERVENTIONS}
 * 决定的是**按钮显不显示**，那是可绕过的。真正的约束必须在服务端。
 * 两份定义必须逐条对齐，任何一边改了另一边不改，就会出现「按钮点得动但服务端拒绝」
 * 或更糟的「按钮不显示但接口能调」。
 */
public final class InterventionDtos {

    private InterventionDtos() {
    }

    /** 干预入参。{@code reason} 必填；{@code compensate} 必带 {@code amount}。 */
    public record InterveneReq(String action, String reason, BigDecimal amount, String operatorName) {
    }

    /** 干预留痕行，镜像前端 {@code OrderIntervention}。 */
    public record OrderIntervention(String interventionNo, String orderNo, String action,
                                    String operatorName, String reason, BigDecimal amount,
                                    String currency, String beforeStatus, String afterStatus,
                                    String createdAt) {
    }

    /** 干预返回：落库后的订单 + 刚写入的留痕（抽屉据此同步状态与时间线）。 */
    public record OrderInterveneResult(RentOrder order, OrderIntervention intervention) {
    }

    /**
     * 允许的动作 → （合法的起始状态集合，目标状态）。
     *
     * <p>{@code null} 目标 = <b>只留痕不改状态</b>（免单/补偿/退款申请改的是钱，不是订单状态）。
     *
     * <p>口径与 {@code order.ts} 的 {@code ORDER_INTERVENTIONS} 逐条一致：
     * 已弹出的宝不能再「远程弹出」，故 {@code eject} 不含 {@code IN_USE} 及之后；
     * 已关闭订单不再动账（{@code waive} 不含 {@code CLOSED}），但事后补偿仍允许。
     */
    public static final Map<String, Rule> RULES = Map.of(
            "eject", new Rule(Set.of("CREATED", "DISPENSING", "EXCEPTION"), "DISPENSING"),
            "force_return", new Rule(Set.of("DISPENSING", "IN_USE", "EXCEPTION"), "SETTLED"),
            "waive", new Rule(Set.of("IN_USE", "RETURNED", "SETTLED", "EXCEPTION"), null),
            "compensate", new Rule(Set.of("IN_USE", "RETURNED", "SETTLED", "CLOSED", "EXCEPTION"), null),
            "refund_apply", new Rule(Set.of("RETURNED", "SETTLED", "CLOSED", "EXCEPTION"), null));

    /** 动作列表（稳定顺序，供报错信息与文档使用）。 */
    public static final List<String> ACTIONS =
            List.of("eject", "force_return", "waive", "compensate", "refund_apply");

    /** @param to {@code null} = 状态不变 */
    public record Rule(Set<String> from, String to) {
    }
}
