package ai.neargo.sharehub.loc;

import java.util.Arrays;

/**
 * 站点状态（2026-09-25 定：与门店生命周期合并为一套）。与 ops-web {@code SiteStatus} 同名同值。
 * 签约前的「拓展中」归商机，不归站点 —— 签约前站点还不存在。
 */
public enum SiteStatus {
    /** 筹备中：已建档，还没有设备上线。 */
    PREPARING,
    /** 营业中：唯一可借的状态。 */
    ACTIVE,
    /** 暂停营业：停借保还。 */
    PAUSED,
    /** 撤场中：停借保还，等撤机与在借订单归还。 */
    WITHDRAWING,
    /** 已关闭：无设备、无未结工单、无在借订单后才能进入；之后可归档。 */
    CLOSED;

    public static SiteStatus of(String v) {
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("站点状态非法: " + v));
    }
}
