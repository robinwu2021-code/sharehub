package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankRow;

/**
 * 充电宝疑似丢失（V113）：失联满 N 天只打标记，由人核实后「确认丢失」或「已找回」，不自动转 LOST。
 * 为什么不自动转，见迁移 V113 的说明。
 */
public interface PowerbankLossService {

    /**
     * 定时扫描（{@code powerbank-lost-suspect}）：标记新的疑似、清掉已不成立的、升级久未处理的。
     */
    ScanResult scan();

    /** 确认丢失：RENTED → LOST（状态机 CONFIRM_LOST），清标记。只对疑似中的宝开放。 */
    PowerbankRow confirmLost(String powerbankNo, String note);

    /** 已找回 / 误判：清标记并重置失联计时。说明必填。 */
    PowerbankRow dismiss(String powerbankNo, String note);

    record ScanResult(int marked, int cleared, int escalated) {
    }
}
