package ai.neargo.sharehub.inv.service;

import ai.neargo.sharehub.inv.dto.InventoryReportDtos.InventoryReport;

import java.time.LocalDate;

/** 库存报表（V206）：汇总已有视图（阶段、期间报表、安全库存）与单据（处置、入库单金额），不另起口径。 */
public interface InventoryReportService {

    /** from / to 空取本月 1 日至今天；跨度最多 366 天。 */
    InventoryReport report(LocalDate from, LocalDate to, String warehouseNo);
}
