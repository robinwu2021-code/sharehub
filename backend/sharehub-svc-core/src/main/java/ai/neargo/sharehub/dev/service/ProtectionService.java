package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import ai.neargo.sharehub.api.core.port.DeviceProtectionPort;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.Protection;

import java.util.List;

/**
 * 设备保护动作（引用计数）：同一目标可被多个持有者同时要求，全部释放才解除。
 * 每次变更后回写 {@code dev_cabinet.available_count}。
 */
public interface ProtectionService extends DeviceProtectionPort {

    Protection applyManual(String cabinetNo, Integer slotIndex, String action, String reason);

    /** 只能释放 MANUAL 持有的；信号 / 告警持有的由恢复信号 / 告警关闭释放。 */
    Protection releaseManual(String protectionNo, String reason);

    List<Protection> list(String cabinetNo, boolean activeOnly);

    void applyBySignal(DeviceSignalEvent s, String action);

    /** 恢复信号解除对应信号持有的保护。 */
    int releaseBySignal(String clearedCode, String cabinetNo, Integer slotIndex, String reason);
}
