package ai.neargo.sharehub.loc.ext.dto;

/** 线索跟进与合同附件的出入参，镜像 ops-web 同名 interface。 */
public final class LocExtDtos2 {

    private LocExtDtos2() {
    }

    /** 线索跟进记录行。 */
    public record LeadFollowUp(String followNo, String leadNo, String channel,
                               String fromStage, String toStage, String owner,
                               String content, String nextAt, String createdAt) {
    }

    /** 跟进入参。{@code toStage} 与来源相同表示本次未推进阶段。 */
    public record LeadFollowUpReq(String channel, String toStage, String content, String nextAt) {
    }

    /**
     * 合同附件元数据。
     *
     * <p><b>没有 url / storageKey 是刻意的</b>：接对象存储前编一个假地址，
     * 「点开看不了」比「明确没有下载入口」更难查。
     */
    public record ContractAttachment(String attachNo, String fileName, Long size,
                                     String uploadedBy, String uploadedAt) {
    }
}
