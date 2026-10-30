package ai.neargo.sharehub.inv;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.dev.port.WarehouseLookup;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.entity.InvWarehouse;
import ai.neargo.sharehub.inv.mapper.InvWarehouseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 仓库目录（过渡期，独立进销存上线前；见 docs/technical/独立进销存/D §2.10）。
 *
 * <p>仓库由迁移种入（V182）；增改归档见 {@link ai.neargo.sharehub.inv.service.WarehouseAdminService}（V201，用户 2026-10-29
 * 要求补仓库管理）。切到独立进销存后，本地仓号仍是主数据，库存服务侧按名称认领 / 建仓（InventoryLinks）。
 *
 * <p>「默认回仓仓」的规则只有这一处：撤机回仓（调拨单终点）、出库池退回仓库（不填仓时）、
 * 只读列表的默认标记都读它。此前它只在撤机回仓里，出库池退回不填仓时设备会落到「在库、不在任何仓、也不在出库池」。
 */
@Component
public class WarehouseDirectory implements WarehouseLookup {

    static final String RETURN_PARAM = "inv.return_warehouse";

    private final InvWarehouseMapper warehouses;
    private final SysParamPort params;

    public WarehouseDirectory(InvWarehouseMapper warehouses, SysParamPort params) {
        this.warehouses = warehouses;
        this.params = params;
    }

    public List<WarehouseItem> list() {
        String def = defaultReturnWarehouse();
        return warehouses.selectList(new LambdaQueryWrapper<InvWarehouse>().orderByAsc(InvWarehouse::getId)).stream()
                .map(w -> new WarehouseItem(w.getWarehouseNo(), w.getName(), w.getRegionId(), w.getAddress(),
                        w.getWarehouseNo().equals(def), w.getArchivedAt() == null ? null : w.getArchivedAt().toString()))
                .toList();
    }

    /** null表示ALL或后台上下文；空集合表示收窄主体没有任何可见仓库。 */
    public List<String> visibleWarehouseNos() {
        var scope = ai.neargo.sharehub.auth.StaffContext.dataScope();
        if (scope == null || scope.all()) return null;
        return warehouses.selectList(new LambdaQueryWrapper<InvWarehouse>()).stream().map(InvWarehouse::getWarehouseNo).toList();
    }

    @Override
    public void requireVisible(String warehouseNo) {
        List<String> visible = visibleWarehouseNos();
        if (visible != null && !visible.contains(warehouseNo))
            throw new org.springframework.security.access.AccessDeniedException("inventory warehouse scope");
    }

    /** 收发任一方的授权仓管可查看调拨凭证；实际写作业仍逐仓校验。 */
    public void requireAnyVisible(String source, String target) {
        List<String> visible = visibleWarehouseNos();
        if (visible != null && !visible.contains(source) && !visible.contains(target))
            throw new org.springframework.security.access.AccessDeniedException("inventory warehouse scope");
    }

    /** 默认回仓的仓库行；一个仓都没有返回 null。 */
    public InvWarehouse defaultReturn() {
        String configured = params.textOf(RETURN_PARAM, null);
        if (configured != null) {
            InvWarehouse w = byNo(configured);
            if (w != null && w.getArchivedAt() == null) return w;
            // 默认仓不可见时不能悄悄换成另一个仓，写入方须显式选择授权仓库。
            if (visibleWarehouseNos() != null) return null;
        }
        // 没配或配的仓不存在：取第一个仓 —— 单仓运营时不必额外配置
        return warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().isNull(InvWarehouse::getArchivedAt)
                .orderByAsc(InvWarehouse::getId).last("limit 1"));
    }

    /** 启用中的仓（已归档的仓不能再收货 / 退回 / 调入）。 */
    @Override
    public boolean exists(String warehouseNo) {
        InvWarehouse w = warehouseNo == null ? null : byNo(warehouseNo);
        return w != null && w.getArchivedAt() == null;
    }

    @Override
    public String defaultReturnWarehouse() {
        InvWarehouse w = defaultReturn();
        return w == null ? null : w.getWarehouseNo();
    }

    private InvWarehouse byNo(String no) {
        return warehouses.selectOne(new LambdaQueryWrapper<InvWarehouse>().eq(InvWarehouse::getWarehouseNo, no).last("limit 1"));
    }
}
