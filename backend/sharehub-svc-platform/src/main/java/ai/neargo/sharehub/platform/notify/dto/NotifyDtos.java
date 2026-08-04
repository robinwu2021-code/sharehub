package ai.neargo.sharehub.platform.notify.dto;

import java.math.BigDecimal;

/**
 * platform/notify 子域出参 VO 与入参体。
 *
 * <p>字段镜像 ops-web {@code lib/types/system.ts}（{@code NotifyTemplate}/{@code NotifyLog}/
 * {@code NotifyLogStats}/{@code NotifyBlacklist}）。
 */
public final class NotifyDtos {

    private NotifyDtos() {
    }

    /** 通知模板行，镜像前端 {@code NotifyTemplate}（多出 scene/content，供编辑抽屉用）。 */
    public record NotifyTemplateVO(String templateNo, String name, String channel, String lang,
                                   String scene, String content, String params, String status) {
    }

    /** 发送记录行，镜像前端 {@code NotifyLog}；{@code target} 已脱敏。 */
    public record NotifyLogVO(String logNo, String channel, String templateNo, String target,
                              String scene, String sentAt, String status, String failReason,
                              BigDecimal cost, String currency,
                              String idempotencyKey, String resendOf) {
    }

    /** 模板预览出参，镜像前端 {@code NotifyTemplatePreview}。 */
    public record NotifyTemplatePreviewVO(String templateNo, String channel, String rendered,
                                          java.util.List<String> missingVars) {
    }

    /** 试发入参，镜像前端 {@code NotifyTestSendPayload}。 */
    public record NotifyTestSendReq(String target, java.util.Map<String, String> vars,
                                    String idempotencyKey) {
    }

    /** 重发入参：**只有幂等键** —— 目标/渠道/模板一律沿用原记录，改这些等于换了一次发送。 */
    public record NotifyResendReq(String idempotencyKey) {
    }

    /**
     * 发送记录页头统计，镜像前端 {@code NotifyLogStats}。
     *
     * <p><b>全量口径</b>（[api/README §7.3]）：统计走独立聚合查询，不是"当前分页那 10 条"的合计
     * —— 页内合计会随翻页变化，是运营侧最常见的口径错误。
     */
    public record NotifyLogStats(long sentToday, long failedToday, BigDecimal failRate,
                                 BigDecimal costToday, String currency) {
    }

    /** 触达拉黑行，镜像前端 {@code NotifyBlacklist}（多出 releasedAt/releasedBy/status，解除后要看留痕）。 */
    public record NotifyBlacklistVO(String blockNo, String target, String channel, String reason,
                                    String blockedAt, String blockedBy, String expireAt,
                                    String releasedAt, String releasedBy, String status) {
    }

    /**
     * 域间发送入参（{@code POST /internal/platform/notify/send}）。
     *
     * <p>{@code target} 由调用方传**明文**（它才拿得到），本域落库前脱敏 —— 明文不出本方法。
     */
    public record SendReq(String channel, String templateNo, String target, String scene,
                          String content, BigDecimal cost, String currency) {
    }

    /**
     * 发送结果。{@code blocked=true} 表示被触达黑名单拦下（此时 {@code status=FAILED}、
     * {@code failReason=BLACKLISTED}），调用方据此决定是否降级到其它渠道。
     */
    public record SendResult(String logNo, String status, boolean blocked, String failReason) {
    }
}
