package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.DisposalDtos.Disposal;
import ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalDetail;
import ai.neargo.sharehub.inv.dto.DisposalDtos.DisposalReq;

/**
 * 处置出库（V203）：在库设备报废 / 退供应商。设备须在该仓、在库、未绑点位、不在出库池、不在未结待检单上；
 * 不符的一次列全、整单不建。独立进销存引擎（SERVICE）下同事务在库存服务做一张出库（V210 起）。
 */
public interface DisposalService {

    PageResult<Disposal> page(Integer page, Integer size, String keyword, String kind, String warehouseNo, String supplierNo);

    DisposalDetail get(String disposalNo);

    DisposalDetail create(DisposalReq req);
}
