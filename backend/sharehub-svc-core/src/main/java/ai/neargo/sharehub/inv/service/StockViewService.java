package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.StockDtos.InventoryEngine;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCount;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail;
import ai.neargo.sharehub.inv.dto.StockDtos.StockLedgerRow;

import java.util.List;

/**
 * 库存流水与盘点（S4，D §2.4 ⑥）。流水两个引擎都有（本地取进出仓履历）；盘点是独立进销存的单据，
 * 本地引擎下 409（本地没有盘点账）。
 */
public interface StockViewService {

    InventoryEngine engine();

    /** 统一库存视图（S6）：取本地投影，两个引擎同口径。 */
    List<ai.neargo.sharehub.inv.dto.StockDtos.StockStage> stages();

    /**
     * 设备库存余额（对接明细 B01）：库存服务按仓 × 设备类型的在手 / 占用 / 不可用 / 可用，只列可见的启用仓。
     * 只在独立进销存引擎；库存服务查不到时报错，不当 0。
     */
    java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.DeviceBalance> deviceBalances(String warehouseNo, String itemType);

    /** 某仓在库的设备序列号（余额下钻），按序列号排序分页。 */
    ai.neargo.common.core.PageResult<ai.neargo.sharehub.inv.dto.StockDtos.DeviceSerial> deviceSerials(String warehouseNo, String itemType,
                                                                                                 Integer page, Integer size);

    /** 可出库的柜（C01）：只列可见仓；warehouseNo 可空 = 全部可见仓。最多 2000 台。 */
    java.util.List<ai.neargo.sharehub.inv.dto.StockDtos.IssueCandidate> issueCandidates(String warehouseNo);

    /** 某商家的待安装柜（C05），宿主内分页（库存服务出库池暂无分页，需求 #5）。 */
    ai.neargo.common.core.PageResult<ai.neargo.sharehub.inv.dto.StockDtos.InstallCandidate> installationCandidates(String agentNo, String siteNo,
                                                                                                         Integer page, Integer size);

    /** 阶段下钻（库存缺口 P2 #6）：某阶段 × 设备类型的设备清单（最多 2000 台，total 为全量），口径同 {@link #stages()}。 */
    ai.neargo.sharehub.inv.dto.StockDtos.StageItems stageItems(String stage, String itemType);

    /** 期间报表（S6）：SERVICE 读库存服务期间汇总；LOCAL 由当前在仓数与履历倒推（履历自 V196 起）。 */
    List<ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow> periodSummary(java.time.LocalDate from, java.time.LocalDate to,
                                                                          String warehouseNo);

    PageResult<StockLedgerRow> ledger(String warehouseNo, String itemType, String keyword, Integer page, Integer size);

    List<StockCount> counts(String warehouseNo, String status);

    StockCountDetail openCount(String warehouseNo);

    StockCountDetail count(String countNo);

    StockCountDetail scan(String countNo, List<String> serialNos);

    /** 数量物料实盘录入（B15）：只在盘点中、未送审时。 */
    StockCountDetail countLine(String countNo, String itemId, Integer countedQty);

    StockCountDetail submit(String countNo);

    StockCountDetail approve(String countNo, String note);

    StockCountDetail reject(String countNo, String note);

    StockCountDetail post(String countNo);

    StockCountDetail cancel(String countNo);
}
