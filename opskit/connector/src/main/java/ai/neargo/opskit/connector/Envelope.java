package ai.neargo.opskit.connector;

import tools.jackson.databind.JsonNode;

/**
 * webhook 信封（21/06 §六）。{@code data} 按事件类型不同；操作人字段（actor / decidedBy / assignee）是
 * {@code {ref, externalRef?, displayName?}}，宿主编号在 externalRef。
 */
public record Envelope(String eventId, String eventType, String accountId, String workspaceId, String workspaceExternalRef, long occurredAt,
        String idemKey, JsonNode data) {

    static Envelope of(JsonNode n) {
        return new Envelope(n.path("eventId").asString(), n.path("eventType").asString(), n.path("accountId").asString(), n.path("workspaceId").asString(),
                n.path("workspaceExternalRef").asString(null), n.path("occurredAt").asLong(), n.path("idemKey").asString(), n.path("data"));
    }

    /** 同一实例 / 工单内的序号（instance.* · ticket.*）；没有时为 -1。接收方存最后处理的 seq，收到更小的直接丢弃。 */
    public int seq() {
        return data.path("seq").asInt(-1);
    }

    /** data 里的业务对象 {type, ref}；没有时为 null。 */
    public String subjectType() {
        return data.path("subject").path("type").asString(null);
    }

    public String subjectRef() {
        return data.path("subject").path("ref").asString(null);
    }

    /** 操作人字段的宿主编号（如 {@code externalRef("decidedBy")}）；非成员（应用 / 系统）为 null。 */
    public String externalRef(String memberField) {
        return data.path(memberField).path("externalRef").asString(null);
    }
}
