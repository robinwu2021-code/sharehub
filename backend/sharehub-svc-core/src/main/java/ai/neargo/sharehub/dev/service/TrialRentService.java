package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.TrialRent;

import java.util.List;

/** 试借还（上线门禁 TRIAL 项）。走设备指令与信号，不经下单、不计费。 */
public interface TrialRentService {

    /** 选一个有宝、未被禁用 / 锁定、电量最高的仓，下发 EJECT。 */
    TrialRent start(String cabinetNo);

    List<TrialRent> list(String cabinetNo);

    /** 指令回执 / 仓位识别到宝（DeviceSignalService 同事务内调用）。 */
    void onSignal(DeviceSignalEvent s);

    /** 超 15 分钟未完成 → EXPIRED；返回条数。 */
    int expireStale();
}
