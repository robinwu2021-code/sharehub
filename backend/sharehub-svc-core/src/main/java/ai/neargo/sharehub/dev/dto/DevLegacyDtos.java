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

    public record Cabinet(String cabinetNo, String sn, String vendorCode, String model,
                          String locationNo, String locationName, int slotTotal, int availableCount,
                          String onlineStatus, String status, String fwVersion, String lastHeartbeatAt,
                          String siteNo) {

        /** 兼容旧 12 参调用（种子/历史代码），siteNo 缺省 null。 */
        public Cabinet(String cabinetNo, String sn, String vendorCode, String model,
                       String locationNo, String locationName, int slotTotal, int availableCount,
                       String onlineStatus, String status, String fwVersion, String lastHeartbeatAt) {
            this(cabinetNo, sn, vendorCode, model, locationNo, locationName, slotTotal, availableCount,
                    onlineStatus, status, fwVersion, lastHeartbeatAt, null);
        }
    }

    public record CabinetDetail(Cabinet cabinet, List<Slot> slots) {
    }

    public record Slot(int slotIndex, String powerbankNo, Integer battery, String lockStatus, String health) {
    }

    public record CommandResult(String commandId) {
    }

}
