package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcRecord;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;

import java.util.List;

/** 入库质检（对齐清单 C3）：记录追加，设备上的 qc_status 取最近一次结论。未通过不能调拨发货、不能上线。 */
public interface QcService {

    QcRecord inspectCabinet(String cabinetNo, QcReq req);

    QcRecord inspectPowerbank(String powerbankNo, QcReq req);

    List<QcRecord> records(String itemNo);
}
