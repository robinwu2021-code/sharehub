package ai.neargo.sharehub.api.core.port;

import java.util.List;

/**
 * 业务告警（ops）申请 / 释放设备保护动作。持有者固定为 ALARM + 告警号；设备域按引用计数合并多方要求。
 * 系统调用，豁免数据范围。
 */
public interface DeviceProtectionPort {

    /**
     * 申请保护。幂等：同一告警对同一目标重复申请返回同一条。
     *
     * @param slotIndex 仓位；null = 整柜
     * @param action    STOP_RENT / SLOT_DISABLE / SLOT_LOCK / DERATE
     * @return 保护编号
     */
    String apply(String cabinetNo, Integer slotIndex, String action, String alarmNo, String reason);

    /** 释放该告警持有的全部动作；返回释放条数。 */
    int release(String alarmNo, String releaseReason);

    /** 解除某信号持有的保护（安全类告警经现场处置验收后调用：该类信号没有「恢复」信号）。返回解除条数。 */
    int releaseSignal(String cabinetNo, Integer slotIndex, String signalCode, String reason);

    /** 生效中、由设备信号持有的仓位锁定（电池安全兜底：没有对应未关闭告警的补开 BATTERY_HAZARD）。 */
    List<SignalLock> activeSignalLocks(int limit);

    record SignalLock(String cabinetNo, Integer slotIndex, String holderRef, String siteNo, String agentNo) {
    }
}
