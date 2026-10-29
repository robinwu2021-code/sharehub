package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;
import ai.neargo.sharehub.inv.dto.IntakeDtos.CreateReq;
import ai.neargo.sharehub.inv.dto.IntakeDtos.DisposeReq;
import ai.neargo.sharehub.inv.dto.IntakeDtos.Intake;
import ai.neargo.sharehub.inv.dto.IntakeDtos.IntakeDetail;
import ai.neargo.sharehub.inv.dto.IntakeDtos.ItemsReq;

/** 到仓待检（V192；docs/technical/独立进销存/D §1.3）。 */
public interface IntakeService {

    PageResult<Intake> page(Integer page, Integer size, String keyword, String status, String warehouseNo);

    IntakeDetail get(String intakeNo);

    /** 到货登记。 */
    IntakeDetail create(CreateReq req);

    /** 逐台质检（复用设备质检），结论写回明细。 */
    IntakeDetail inspect(String intakeNo, String itemType, String itemNo, QcReq req);

    /** 质检合格的入库：本地模式下 = 设备所在仓写成本单的仓。 */
    IntakeDetail inbound(String intakeNo, ItemsReq req);

    /** 质检不合格的处置：退供应商 / 报废，说明必填。 */
    IntakeDetail dispose(String intakeNo, DisposeReq req);
}
