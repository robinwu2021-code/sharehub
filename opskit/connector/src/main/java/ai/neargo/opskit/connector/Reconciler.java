package ai.neargo.opskit.connector;

import tools.jackson.databind.JsonNode;

/**
 * 对账（21/07 X1 · 23/§3.3）：宿主里「等待审批太久」的业务行，逐条问 OpsKit 这张单到底怎样了。
 * 兜的是 webhook 丢失 / 处理失败耗尽重投这类「两边说法不一」：
 *
 * <pre>
 * NOT_FOUND    OpsKit 查无此单            → 送审没成功：标「送审失败」进重送
 * IN_PROGRESS  还在审                      → 什么也不做
 * APPROVED / REJECTED / WITHDRAWN          → 按结论补推进业务行（与 webhook 处理走同一段代码），并记 WARN
 * CLOSED       实例已结束但不是审批结论    → 人工看（流程被作废等）
 * </pre>
 */
public final class Reconciler {

    public enum State { NOT_FOUND, IN_PROGRESS, APPROVED, REJECTED, WITHDRAWN, CLOSED }

    public record Outcome(State state, String instanceNo, String approvalNo, String status, String statusCategory) {
    }

    private final OpsKitConnector connector;

    public Reconciler(OpsKitConnector connector) {
        this.connector = connector;
    }

    /** 该业务对象上、该流程最新的一个实例的结论。 */
    public Outcome check(String subjectType, String subjectRef, String processCode) {
        JsonNode h = connector.subjectHistory(subjectType, subjectRef);
        for (JsonNode item : h.path("instances")) { // 服务端按最新在前
            JsonNode inst = item.path("instance");
            if (processCode != null && !processCode.equals(inst.path("process").path("code").asString())) {
                continue;
            }
            String no = inst.path("instanceNo").asString();
            String status = inst.path("status").path("code").asString();
            String category = inst.path("statusCategory").asString();
            JsonNode approval = latest(item.path("approvals"));
            String approvalNo = approval == null ? null : approval.path("approvalNo").asString();
            if (inst.path("open").asBoolean()) {
                return new Outcome(State.IN_PROGRESS, no, approvalNo, status, category);
            }
            State s = approval == null ? State.CLOSED : switch (approval.path("status").asString()) {
                case "APPROVED" -> State.APPROVED;
                case "REJECTED" -> State.REJECTED;
                case "WITHDRAWN" -> State.WITHDRAWN;
                default -> State.CLOSED;
            };
            return new Outcome(s, no, approvalNo, status, category);
        }
        return new Outcome(State.NOT_FOUND, null, null, null, null);
    }

    private static JsonNode latest(JsonNode approvals) {
        JsonNode best = null;
        for (JsonNode a : approvals) {
            if (best == null || a.path("requestedAtMs").asLong() >= best.path("requestedAtMs").asLong()) {
                best = a;
            }
        }
        return best;
    }
}
