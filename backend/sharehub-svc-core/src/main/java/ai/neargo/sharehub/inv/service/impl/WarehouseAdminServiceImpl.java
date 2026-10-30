package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.inv.IntakeStatus;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseReq;
import ai.neargo.sharehub.inv.entity.InvIntake;
import ai.neargo.sharehub.inv.entity.InvWarehouse;
import ai.neargo.sharehub.inv.mapper.InvIntakeMapper;
import ai.neargo.sharehub.inv.mapper.InvWarehouseMapper;
import ai.neargo.sharehub.inv.service.WarehouseAdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 仓库管理（V201），规则见 {@link WarehouseAdminService}。 */
@Service
public class WarehouseAdminServiceImpl implements WarehouseAdminService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseAdminServiceImpl.class);

    private final InvWarehouseMapper warehouses;
    private final WarehouseDirectory directory;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final InvIntakeMapper intakes;

    public WarehouseAdminServiceImpl(InvWarehouseMapper warehouses, WarehouseDirectory directory, CabinetMapper cabinets,
                                     PowerbankMapper powerbanks, InvIntakeMapper intakes) {
        this.warehouses = warehouses;
        this.directory = directory;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.intakes = intakes;
    }

    @Override
    @Transactional
    public WarehouseItem create(WarehouseReq req) {
        InvWarehouse w = new InvWarehouse();
        w.setWarehouseNo(IdGenerator.next("WH"));
        apply(w, req);
        warehouses.insert(w);
        log.info("仓库新建 warehouseNo={} name={}", w.getWarehouseNo(), w.getName());
        return item(w.getWarehouseNo());
    }

    @Override
    @Transactional
    public WarehouseItem update(String warehouseNo, WarehouseReq req) {
        InvWarehouse w = require(warehouseNo);
        apply(w, req);
        warehouses.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo, warehouseNo)
                .set(InvWarehouse::getName, w.getName()).set(InvWarehouse::getRegionId, w.getRegionId())
                .set(InvWarehouse::getAddress, w.getAddress()));
        return item(warehouseNo);
    }

    @Override
    @Transactional
    public WarehouseItem archive(String warehouseNo) {
        InvWarehouse w = require(warehouseNo);
        if (w.getArchivedAt() != null) return item(warehouseNo);
        List<String> why = new ArrayList<>();
        if (warehouseNo.equals(directory.defaultReturnWarehouse())) why.add("default return warehouse");
        long cab = cabinets.selectCount(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getWarehouseNo, warehouseNo));
        long pb = powerbanks.selectCount(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getWarehouseNo, warehouseNo));
        if (cab + pb > 0) why.add("devices in stock: " + (cab + pb));
        long open = intakes.selectCount(new LambdaQueryWrapper<InvIntake>().eq(InvIntake::getWarehouseNo, warehouseNo)
                .eq(InvIntake::getStatus, IntakeStatus.OPEN.name()));
        if (open > 0) why.add("open intakes: " + open);
        if (!why.isEmpty()) throw BizException.conflict("error.warehouse.in_use", String.join("; ", why));
        w.setArchivedAt(LocalDateTime.now());
        warehouses.updateById(w);
        log.info("仓库归档 warehouseNo={}", warehouseNo);
        return item(warehouseNo);
    }

    @Override
    @Transactional
    public WarehouseItem unarchive(String warehouseNo) {
        InvWarehouse w = require(warehouseNo);
        if (w.getArchivedAt() != null) {
            w.setArchivedAt(null);
            // updateById 跳过 null 字段，清归档时间须显式 set
            warehouses.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvWarehouse>()
                    .eq(InvWarehouse::getWarehouseNo, w.getWarehouseNo()).set(InvWarehouse::getArchivedAt, null));
            log.info("仓库取消归档 warehouseNo={}", warehouseNo);
        }
        return item(warehouseNo);
    }

    private static void apply(InvWarehouse w, WarehouseReq r) {
        if (r == null || r.name() == null || r.name().isBlank())
            throw BizException.badRequest("error.common.missing_parameter", "name");
        w.setName(r.name().trim());
        w.setRegionId(r.regionId() == null || r.regionId().isBlank() ? null : r.regionId().trim());
        w.setAddress(r.address() == null || r.address().isBlank() ? null : r.address().trim());
    }

    private InvWarehouse require(String no) {
        InvWarehouse w = no == null ? null
                : warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo, no).last("limit 1"));
        if (w == null) throw BizException.notFound(no);
        return w;
    }

    private WarehouseItem item(String no) {
        return directory.list().stream().filter(x -> x.warehouseNo().equals(no)).findFirst().orElseThrow();
    }
}
