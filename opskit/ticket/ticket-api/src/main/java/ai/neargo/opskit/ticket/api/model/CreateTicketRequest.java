package ai.neargo.opskit.ticket.api.model;

import java.util.Map;
import java.util.Set;

/**
 * 建单（21/06 §五）。
 *
 * @param requester 提出人（{@code member:x} 或成员 ID）；空 = 操作人本人（应用代成员时为该成员）
 * @param scopes    显式范围标签，与类型 scopeFrom 派生的取并集
 * @param dedupeKey 显式去重键；空 = 按类型的 dedupe 字段派生
 */
public record CreateTicketRequest(String typeCode, String title, String description, Map<String, Object> fields, String priority, String requester,
        Set<String> scopes, String dedupeKey) {
    public CreateTicketRequest {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
