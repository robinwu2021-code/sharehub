package ai.neargo.sharehub.dev.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * dev 域的骨架期 DTO（自顶层 {@code dto.Dto} 归位）。
 *
 * <p>名字带 Legacy 是刻意的：它们是骨架期的形状，字段与当下的实体并不完全对齐。
 * 留在顶层共享集合里会让 svc-core 被迫依赖 app —— 归位只解决依赖，**不代表它们已经定型**。
 */
public final class DevLegacyDtos {

    private DevLegacyDtos() {
    }

    /**
     * @param agentNo    归属代理；`null` = 平台直营。前端列表按它显示归属，缺了就永远是「平台直营」
     * @param archivedAt 归档时间；`null` = 在用。运营端靠它把归档行置灰并显示归档时间
     */
    public record Cabinet(String cabinetNo, String sn, String vendorCode, String model,
                          String locationNo, String locationName, int slotTotal, int availableCount,
                          String onlineStatus, String status, String fwVersion, String lastHeartbeatAt,
                          String siteNo, String agentNo, String archivedAt) {

        /** 兼容旧 12 参调用（种子/历史代码），siteNo / agentNo / archivedAt 缺省 null。 */
        public Cabinet(String cabinetNo, String sn, String vendorCode, String model,
                       String locationNo, String locationName, int slotTotal, int availableCount,
                       String onlineStatus, String status, String fwVersion, String lastHeartbeatAt) {
            this(cabinetNo, sn, vendorCode, model, locationNo, locationName, slotTotal, availableCount,
                    onlineStatus, status, fwVersion, lastHeartbeatAt, null, null, null);
        }

        /** 兼容旧 13 参调用（带 siteNo）。 */
        public Cabinet(String cabinetNo, String sn, String vendorCode, String model,
                       String locationNo, String locationName, int slotTotal, int availableCount,
                       String onlineStatus, String status, String fwVersion, String lastHeartbeatAt,
                       String siteNo) {
            this(cabinetNo, sn, vendorCode, model, locationNo, locationName, slotTotal, availableCount,
                    onlineStatus, status, fwVersion, lastHeartbeatAt, siteNo, null, null);
        }
    }

    public record CabinetDetail(Cabinet cabinet, List<Slot> slots) {
    }

    public record Slot(int slotIndex, String powerbankNo, Integer battery, String lockStatus, String health) {
    }

    public record CommandResult(String commandId) {
    }

}
