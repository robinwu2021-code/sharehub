package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.QcStatus;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcRecord;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.QcReq;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevQcRecord;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.QcRecordMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.QcService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 入库质检实现。只在「在仓」时质检：已布放的设备出问题走故障 / 维修，不走入库质检。 */
@Service
public class QcServiceImpl implements QcService {

    private static final Logger log = LoggerFactory.getLogger(QcServiceImpl.class);

    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final QcRecordMapper records;
    private final SysParamPort params;

    public QcServiceImpl(CabinetMapper cabinets, PowerbankMapper powerbanks, QcRecordMapper records, SysParamPort params) {
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.records = records;
        this.params = params;
    }

    @Override
    @Transactional
    public QcRecord inspectCabinet(String cabinetNo, QcReq r) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) throw BizException.conflict("error.qc.in_stock_only", cabinetNo);
        QcReq req = require(r);
        if (req.powerOn() == null || req.slotsOk() == null) throw BizException.badRequest("error.common.missing_parameter", "powerOn / slotsOk");
        QcStatus result = decide(req, req.powerOn() && req.slotsOk());
        DevQcRecord rec = record("CABINET", cabinetNo, req, result);
        cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo)
                .set(DevCabinet::getQcStatus, result.name()));
        log.info("机柜入库质检 cabinetNo={} result={}", cabinetNo, result);
        return vo(rec);
    }

    @Override
    @Transactional
    public QcRecord inspectPowerbank(String powerbankNo, QcReq r) {
        DevPowerbank p = powerbanks.selectOne(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, powerbankNo).last("limit 1"));
        if (p == null) throw BizException.notFound(powerbankNo);
        if (!PowerbankStatus.IN_STOCK.name().equals(p.getStatus())) throw BizException.conflict("error.qc.in_stock_only", powerbankNo);
        QcReq req = require(r);
        if (req.battery() == null || req.cycles() == null) throw BizException.badRequest("error.common.missing_parameter", "battery / cycles");
        int minBattery = params.intOf("device.qc.min_battery", 60);
        int maxCycles = params.intOf("device.qc.max_cycles", 500);
        QcStatus result = decide(req, req.battery() >= minBattery && req.cycles() <= maxCycles);
        DevQcRecord rec = record("POWERBANK", powerbankNo, req, result);
        powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, powerbankNo)
                .set(DevPowerbank::getQcStatus, result.name()).set(DevPowerbank::getBattery, req.battery())
                .set(DevPowerbank::getCycles, req.cycles()));
        log.info("充电宝入库质检 powerbankNo={} result={} battery={} cycles={}", powerbankNo, result, req.battery(), req.cycles());
        return vo(rec);
    }

    @Override
    public List<QcRecord> records(String itemNo) {
        return records.selectList(new LambdaQueryWrapper<DevQcRecord>().eq(DevQcRecord::getItemNo, itemNo)
                .orderByDesc(DevQcRecord::getId)).stream().map(QcServiceImpl::vo).toList();
    }

    /** 检查项定结论；人可以把「过了」判成不过（带原因），不能把「不过」判成过。 */
    private static QcStatus decide(QcReq req, boolean checksPass) {
        String explicit = req.result() == null || req.result().isBlank() ? null : req.result().trim().toUpperCase();
        if (QcStatus.FAILED.name().equals(explicit) || (explicit == null && !checksPass)) {
            if (req.note() == null || req.note().isBlank()) throw BizException.badRequest("error.common.note_required");
            return QcStatus.FAILED;
        }
        if (explicit != null && !QcStatus.PASSED.name().equals(explicit)) throw BizException.badRequest("error.common.invalid_value", "result=" + explicit);
        if (!checksPass) throw BizException.badRequest("error.qc.checks_failed");
        return QcStatus.PASSED;
    }

    private DevQcRecord record(String type, String itemNo, QcReq r, QcStatus result) {
        DevQcRecord e = new DevQcRecord();
        e.setQcNo(IdGenerator.next("QC"));
        e.setTenantId("MAIN");
        e.setItemType(type);
        e.setItemNo(itemNo);
        e.setPowerOn(r.powerOn());
        e.setSlotsOk(r.slotsOk());
        e.setBattery(r.battery());
        e.setCycles(r.cycles());
        e.setResult(result.name());
        e.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        e.setInspectedBy(SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM"));
        e.setInspectedAt(LocalDateTime.now());
        records.insert(e);
        return e;
    }

    private static QcReq require(QcReq r) {
        if (r == null) throw BizException.badRequest("error.common.missing_parameter", "body");
        return r;
    }

    private static QcRecord vo(DevQcRecord e) {
        return new QcRecord(e.getQcNo(), e.getItemType(), e.getItemNo(), e.getPowerOn(), e.getSlotsOk(), e.getBattery(),
                e.getCycles(), e.getResult(), e.getNote(), e.getInspectedBy(), e.getInspectedAt());
    }
}
