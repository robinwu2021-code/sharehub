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

    private InventoryPort current() {
        return "SERVICE".equalsIgnoreCase(params.textOf("inv.engine", "LOCAL")) ? remote : local;
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
}
