package ai.neargo.sharehub.cs.dto;

/**
 * user/cs 子域 DTO。出参字段镜像 ops-web {@code lib/types/cs.ts}
 * （{@code CsTicket} / {@code CsSession}），并补上前端尚缺的两个出口字段。
 */
public final class CsDtos {

    private CsDtos() {
    }

    /**
     * 报障受理单行，镜像前端 {@code CsTicket}。
     *
     * <p>比前端多 {@code problemNo}/{@code orderNo}/{@code handlerNo}/{@code woNo}/{@code refundNo}：
     * 后三者是「处置去向可追溯」的关键（[api/README §6A.2]），前端待补。
     */
    public record CsTicketVO(String ticketNo, String userNo, String orderNo, String cabinetNo,
                             String problemNo, String issue, String channel, String status,
                             String handlerNo, String woNo, String refundNo, String createdAt) {
    }

    /** 客服会话行，镜像前端 {@code CsSession}。 */
    public record CsSessionVO(String sessionNo, String userNo, String agentName,
                              String lastMessage, String status, String updatedAt) {
    }

    /** 会话消息行（append 表读出）。 */
    public record CsMessageVO(Long id, String sessionNo, String senderType, String senderNo,
                              String content, String attach, String createdAt) {
    }

    /**
     * 运营端受理/更新报障的入参。
     * {@code status} 只接受 OPEN/PROCESSING/CLOSED，非法迁移由 service 拒绝。
     */
    public record TicketUpdateReq(String status, String handlerNo, String issue) {
    }

    /** C 端自助报障入参（{@code POST /mp/user/report}）。{@code cUserNo} 由会话决定，不从这里取。 */
    public record ReportReq(String problemNo, String orderNo, String cabinetNo,
                            String issue, String channel) {
    }

    /**
     * C 端报障受理结果 —— 除了单号，还告诉用户「被分流到哪」，
     * 让 C 端能直接跳转到工单进度 / 退款进度 / 人工会话。
     *
     * @param suggestedAction 来自 {@code md_problem.suggested_action}：
     *                        SELF_SERVICE / TO_WORKORDER / TO_REFUND / TO_CS
     */
    public record ReportResultVO(String reportNo, String status, String suggestedAction,
                                 String woNo, String refundNo, String sessionNo, String createdAt) {
    }

    /** 客服回复入参。 */
    public record ReplyReq(String content, String attach) {
    }
}
