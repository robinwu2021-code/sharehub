package ai.neargo.sharehub.api.ops.port;

import java.util.Collection;
import java.util.Map;

/** 工单的系统读（站点关闭门禁）。豁免数据范围。 */
public interface WorkOrderQueryPort {

    /** 各站点未关闭的工单数。 */
    Map<String, Long> openCountBySites(Collection<String> siteNos);

    /** 其中已有装机工单完工（DONE / AUDITED / CLOSED）的机柜。设备上线门禁用（C5）。 */
    java.util.Set<String> installDoneCabinets(Collection<String> cabinetNos);
}
