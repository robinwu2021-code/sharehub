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

    /**
     * 运营端手工建单入参（{@code POST /api/ops/cs/tickets}）。
     *
     * <p><b>与 {@link ReportReq} 是两回事，不要合并</b>：
     * 报障是消费者发起、要走**自动分流**（可能直接判为自助解决而关单）；
     * 手工建单是客服接到来电后登记 —— 人已经在处理了，再被分流关掉是荒谬的。
     * 页面空态写的就是「用户来电/APP 报障后在此登记，也可点右上「新增工单」手工建单」。
     *
     * <p>{@code userNo} 在这里**可空**：来电的人未必报得出账号，
     * 而「登记不下来」比「记录里缺个账号」糟得多。（列直到 V119 才真的允许 NULL ——
     * 在那之前这句注释是对的、库是错的，不带账号建单 500。）
     *
     * <p><b>叫 userNo 不叫 cUserNo</b>：本仓库的分层口径是 DTO / API 层用 {@code userNo}，
     * 实体与库列用 {@code cUserNo}（{@code c_user_no}）。同域的 {@code ComplaintCreateReq}、
     * {@code RefundApplyReq} 都是前者，出参 {@link CsTicketVO} 也是前者。
     * 此前这里写成 cUserNo —— 把列名漏到了 API 上，于是运营端发的 userNo 被静默丢掉。
     */
    public record TicketCreateReq(String ticketNo, String userNo, String orderNo, String cabinetNo,
                                  String problemNo, String issue, String channel, String status) {
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
