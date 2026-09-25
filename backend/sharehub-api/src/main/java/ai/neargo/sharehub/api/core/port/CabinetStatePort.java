package ai.neargo.sharehub.api.core.port;

import ai.neargo.sharehub.api.core.dto.CabinetAvailability;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机柜状态的系统读（站点门禁、业务告警判定）。<b>豁免数据范围</b> —— 与 {@link CabinetQueryPort}（保留范围）分开。
 */
public interface CabinetStatePort {

    /** 各站点下、状态属于 {@code statuses} 且未归档的机柜数。 */
    Map<String, Long> countBySites(Collection<String> siteNos, Set<String> statuses);

    /** 各站点下已布放 / 故障机柜的可借可还状态（在线口径：最近心跳 ≤ 3 分钟）。 */
    List<CabinetAvailability> availabilityBySites(Collection<String> siteNos);

    /**
     * 各站点已布放 / 故障机柜里的宝况（批次 D 调度判定）：在柜宝数、其中低电（低于借出下限）的、老化待报废的。
     * 只含有宝台账的柜子 —— 没有台账的旧柜子无从知道每颗宝的电量。
     */
    List<CabinetPower> powerBySites(Collection<String> siteNos);

    record CabinetPower(String cabinetNo, String siteNo, String agentNo, int inCabinet, int lowBattery, int aged) {
    }

    /** 各站点已布放 / 故障机柜在 {@code since} 之后的某信号次数（窗口计数型告警，E1）。没发生过的柜子不出现在结果里。 */
    Map<String, Integer> signalCounts(Collection<String> siteNos, String code, java.time.LocalDateTime since);

    /**
     * 失联的宝（E1 · POWERBANK_MISSING）：处于借出中、却没有进行中订单、也不在在途调拨里，且超过 {@code hours} 小时没有变化。
     */
    List<MissingPowerbank> missingPowerbanks(int hours, int limit);

    record MissingPowerbank(String powerbankNo, String status, java.time.LocalDateTime lastChangedAt, String lastOrderNo,
                            String lastSiteNo) {
    }
}
