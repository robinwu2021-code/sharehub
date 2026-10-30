package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcRecord;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;

import java.util.List;

/** 入库质检（对齐清单 C3）：记录追加，设备上的 qc_status 取最近一次结论。未通过不能调拨发货、不能上线。 */
public interface QcService {

    QcRecord inspectCabinet(String cabinetNo, QcReq req);

    QcRecord inspectPowerbank(String powerbankNo, QcReq req);

    /** 在到仓待检单上质检：记录挂上单号，作为本单入库的放行凭证（V194）。 */
    QcRecord inspectCabinet(String cabinetNo, QcReq req, String intakeNo);

    QcRecord inspectPowerbank(String powerbankNo, QcReq req, String intakeNo);

    /**
     * 用掉放行凭证（PB-C3 / C4）：须是本设备、本待检单上做的、结论通过、还没被用过。
     * 返回 false = 凭证不可用（被用过 / 不是本单的 / 不是通过）。
     */
    boolean consume(String qcNo, String itemNo, String intakeNo);

    List<QcRecord> records(String itemNo);
}
