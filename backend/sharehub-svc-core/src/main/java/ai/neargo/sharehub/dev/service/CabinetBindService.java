package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.BindDtos.BindResult;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;

import java.util.List;

/**
 * 绑定 / 解绑（V164；用户 2026-10-28：绑定的设备只从该商家的出库池里选，默认绑到默认站点的默认点位）。
 *
 * <p>不变式（技术设计 §2.6）：
 * <ul>
 *   <li>I2 点位所属站点的商家 = 设备的出库池商家，否则 409；</li>
 *   <li>I3 机柜的 agent_no 只由点位派生（这里与装机完工改绑共用同一个方法）；</li>
 *   <li>I5 平台直营站点（商家为空）的设备不经出库池：出库池为空也能绑直营站点。</li>
 * </ul>
 */
public interface CabinetBindService {

    BindResult bind(String cabinetNo, String locationNo, boolean openInstallWo, String expectedAt);

    /** 一个事务：任一台不满足，整批不动。 */
    List<BindResult> bindBatch(String agentNo, String locationNo, List<String> cabinetNos, boolean openInstallWo,
                               String expectedAt);

    /** 解绑：仅已绑定、未上线；回到同一商家的出库池。 */
    Cabinet unbind(String cabinetNo, String reason);

    /** 装机完工现场扫到的点位与档案不同：改绑，同样校验 I2。 */
    void rebindForInstall(String cabinetNo, String locationNo);
}
