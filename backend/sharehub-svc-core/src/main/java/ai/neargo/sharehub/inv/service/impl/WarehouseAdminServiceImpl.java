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
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.inv.port.InventoryEngineRouter;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryCalls;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.WarehouseAdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;

/** 仓库管理（V201），规则见 {@link WarehouseAdminService}。 */
@Service
public class WarehouseAdminServiceImpl implements WarehouseAdminService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseAdminServiceImpl.class);

    private final ai.neargo.sharehub.inv.service.InventoryOperationJournal journal;
    private final InvWarehouseMapper warehouses;
    private final WarehouseDirectory directory;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final InvIntakeMapper intakes;

    public WarehouseAdminServiceImpl(InvWarehouseMapper warehouses, WarehouseDirectory directory, CabinetMapper cabinets,
                                     PowerbankMapper powerbanks, InvIntakeMapper intakes, ai.neargo.sharehub.inv.service.InventoryOperationJournal journal) {
        this.journal = journal;
        this.warehouses = warehouses;
        this.directory = directory;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.intakes = intakes;
    }

    /** 独立进销存侧的仓（对接明细 A01）：只在 SERVICE 引擎下同步，LOCAL 不动。 */
    private RemoteInventoryPort remote;
    private InventoryLinks links;
    private SysParamPort params;

    @Autowired
    void setInventory(RemoteInventoryPort remote, InventoryLinks links, SysParamPort params) {
        this.remote = remote;
        this.links = links;
        this.params = params;
    }

    private boolean service() {
        return params != null && InventoryEngineRouter.isService(params);
    }

    /** 已映射到库存服务的 locationId；没映射过返回 null（从没用过的仓，库存服务里还没有它）。 */
    private String mapped(String warehouseNo) {
        return links.get("wh:" + warehouseNo);
    }

    private InventoryApi api() {
        InventoryApi api = remote.api();
        InventoryCalls.requireConfigured(api);
        return api;
    }

    @Override
    @Transactional
    public WarehouseItem create(WarehouseReq req) {
        InvWarehouse w = new InvWarehouse();
        w.setWarehouseNo(IdGenerator.next("WH"));
        apply(w, req);
        requireUniqueName(w.getName(), null);
        warehouses.insert(w);
        if (service()) {
            // 新建即在库存服务建仓并记下映射。映射独立提交：本地若回滚，映射留着无害，同仓号重试走同一幂等键
            InventoryApi api = api();
            journal.reference(() -> InventoryCalls.call(() -> links.locationId(api, w.getWarehouseNo(), w.getName())));
        }
        log.info("仓库新建 warehouseNo={} name={}", w.getWarehouseNo(), w.getName());
        return item(w.getWarehouseNo());
    }

    @Override
    @Transactional
    public WarehouseItem update(String warehouseNo, WarehouseReq req) {
        journal.requireMasterIdle(warehouseNo, "$.warehouseNo", "$.toWarehouseNo");
        InvWarehouse w = require(warehouseNo);
        apply(w, req);
        requireUniqueName(w.getName(), warehouseNo);
        warehouses.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo, warehouseNo)
                .set(InvWarehouse::getName, w.getName()).set(InvWarehouse::getRegionId, w.getRegionId())
                .set(InvWarehouse::getAddress, w.getAddress()));
        String loc = service() ? mapped(warehouseNo) : null;
        if (loc != null) {
            // 改名、改区域同步到库存服务（地址库存服务不存）；幂等键按内容，同内容重放无害
            InventoryApi api = api();
            String content = w.getName() + "|" + w.getRegionId();
            InventoryCalls.run(() -> api.updateWarehouse(loc, w.getName(), w.getRegionId(),
                    "wh-upd-" + warehouseNo + "-" + Integer.toHexString(content.hashCode())));
        }
        return item(warehouseNo);
    }

    @Override
    @Transactional
    public WarehouseItem archive(String warehouseNo) {
        journal.requireMasterIdle(warehouseNo, "$.warehouseNo", "$.toWarehouseNo");
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
        String loc = service() ? mapped(warehouseNo) : null;
        InventoryApi api = loc == null ? null : api();
        if (loc != null) {
            // 物料的账只在库存服务：本地设备表看不到，归档前必须问库存服务
            BigDecimal remoteStock = InventoryCalls.call(() -> api.balances(loc, null)).stream()
                    .map(InventoryApi.Balance::onHand).filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (remoteStock.signum() > 0) why.add("inventory service stock: " + remoteStock.stripTrailingZeros().toPlainString());
        }
        if (!why.isEmpty()) throw BizException.conflict("error.warehouse.in_use", String.join("; ", why));
        if (loc != null) InventoryCalls.run(() -> api.disableWarehouse(loc, "wh-dis-" + warehouseNo));
        w.setArchivedAt(LocalDateTime.now());
        warehouses.updateById(w);
        log.info("仓库归档 warehouseNo={}", warehouseNo);
        return item(warehouseNo);
    }

    @Override
    @Transactional
    public WarehouseItem unarchive(String warehouseNo) {
        InvWarehouse w = require(warehouseNo);
        String loc = service() && w.getArchivedAt() != null ? mapped(warehouseNo) : null;
        if (loc != null) {
            // 库存服务还没有「重新启用」（已列入宿主接口需求）：那边停用着，本地恢复了也写不进账，不如明确拒绝
            boolean disabled = InventoryCalls.call(() -> api().warehouses()).stream()
                    .anyMatch(x -> loc.equals(x.locationId()) && "DISABLED".equals(x.status()));
            if (disabled) throw BizException.conflict("error.inventory.warehouse_enable_unsupported", warehouseNo);
        }
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

    /** 启用中的仓不许同名：库存服务按名称认领，同名就分不清是哪个仓。 */
    private void requireUniqueName(String name, String self) {
        LambdaQueryWrapper<InvWarehouse> q = new LambdaQueryWrapper<InvWarehouse>().eq(InvWarehouse::getName, name)
                .isNull(InvWarehouse::getArchivedAt);
        if (self != null) q.ne(InvWarehouse::getWarehouseNo, self);
        if (warehouses.selectCount(q) > 0) throw BizException.conflict("error.warehouse.name_taken", name);
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
