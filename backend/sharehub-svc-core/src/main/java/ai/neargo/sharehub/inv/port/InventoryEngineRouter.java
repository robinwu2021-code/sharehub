package ai.neargo.sharehub.inv.port;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存引擎开关（D §2.8）：按系统参数 {@code inv.engine} 在本地与独立进销存之间路由，运行时切换、不必重启。
 * 调用方注入 {@link InventoryPort} 拿到的就是它。
 */
@Primary
@Component
public class InventoryEngineRouter implements InventoryPort {

    private final LocalInventoryPort local;
    private final RemoteInventoryPort remote;
    private final SysParamPort params;

    public InventoryEngineRouter(LocalInventoryPort local, RemoteInventoryPort remote, SysParamPort params) {
        this.local = local;
        this.remote = remote;
        this.params = params;
    }

    /** 系统参数键。 */
    public static final String ENGINE_KEY = "inv.engine";
    /**
     * 参数缺失或为空时的引擎：独立进销存（对接明细 F09，库存默认 SERVICE）。
     * 已有库里的值不由代码改 —— 切流是受控操作，见 {@link InventoryEngineGuard}。
     */
    public static final String DEFAULT_ENGINE = "SERVICE";

    /** 当前是否独立进销存引擎。各处判断引擎只用这一个，免得默认值各写各的。 */
    public static boolean isService(SysParamPort params) {
        return "SERVICE".equalsIgnoreCase(params.textOf(ENGINE_KEY, DEFAULT_ENGINE));
    }

    private InventoryPort current() {
        return isService(params) ? remote : local;
    }

    @Override
    public String engine() {
        return current().engine();
    }

    @Override
    public void inbound(String itemType, String itemNo, String warehouseNo, String qcNo, String intakeNo) {
        current().inbound(itemType, itemNo, warehouseNo, qcNo, intakeNo);
    }

    @Override
    public List<Cabinet> dispatch(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
        return current().dispatch(agentNo, siteNo, receiverNo, cabinetNos);
    }

    @Override
    public List<Cabinet> claim(List<String> cabinetNos) {
        return current().claim(cabinetNos);
    }

    @Override
    public List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
        return current().redirect(cabinetNos, agentNo, siteNo, receiverNo, reason);
    }

    @Override
    public List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason) {
        return current().returnToWarehouse(cabinetNos, warehouseNo, reason);
    }

    @Override
    public void transferShip(String transferNo, String itemType, String fromWarehouseNo, List<String> itemNos) {
        current().transferShip(transferNo, itemType, fromWarehouseNo, itemNos);
    }

    @Override
    public void transferReceive(String transferNo, String itemType, String fromWarehouseNo, String toWarehouseNo, List<String> itemNos) {
        current().transferReceive(transferNo, itemType, fromWarehouseNo, toWarehouseNo, itemNos);
    }
}
