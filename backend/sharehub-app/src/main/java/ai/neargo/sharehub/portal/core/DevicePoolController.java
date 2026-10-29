package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.PoolDtos.IssueReq;
import ai.neargo.sharehub.dev.dto.PoolDtos.PoolReturnReq;
import ai.neargo.sharehub.dev.service.CabinetPoolService;
import ai.neargo.sharehub.dev.service.CabinetPoolService.IssuedSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备出库池（V161）：出库给商家、退回仓库、按商家汇总。
 *
 * <p>库存（仓库、调拨单）暂缓，可能由独立服务实现 —— 出库 / 退回是设备上的直接动作，
 * 复用调拨的权限码 {@code device:inventory:transfer}（不新增码，技术设计 §六 #7）。
 */
@RestController
public class DevicePoolController {

    private final CabinetPoolService pool;
    private final ai.neargo.sharehub.dev.service.CabinetBindService binds;
    /** 库存账动作（出库 / 签收 / 调整 / 退回）一律经端口（S2.5）；汇总是只读，仍直接查。 */
    private final ai.neargo.sharehub.inv.port.InventoryPort inventory;

    public DevicePoolController(CabinetPoolService pool, ai.neargo.sharehub.dev.service.CabinetBindService binds,
                                ai.neargo.sharehub.inv.port.InventoryPort inventory) {
        this.pool = pool;
        this.binds = binds;
        this.inventory = inventory;
    }

    /**
     * 绑定（V164）：出库池设备绑到点位；不给点位 = 出库池商家默认站点的默认点位。
     * 点位的商家须 = 出库池商家（否则 409 error.bind.agent_mismatch）。可同事务开装机单。
     */
    @PostMapping("/api/ops/devices/{deviceNo}/bind")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public ai.neargo.sharehub.dev.dto.BindDtos.BindResult bind(@org.springframework.web.bind.annotation.PathVariable String deviceNo,
                                                               @RequestBody(required = false) ai.neargo.sharehub.dev.dto.BindDtos.BindReq req) {
        return binds.bind(deviceNo, req == null ? null : req.locationNo(),
                req != null && Boolean.TRUE.equals(req.openInstallWo()), req == null ? null : req.expectedAt());
    }

    /** 批量绑定：同一商家的一批出库池设备，一个事务（任一台不满足整批不动）。 */
    @PostMapping("/api/ops/devices/bind-batch")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public List<ai.neargo.sharehub.dev.dto.BindDtos.BindResult> bindBatch(@RequestBody ai.neargo.sharehub.dev.dto.BindDtos.BindBatchReq req) {
        return binds.bindBatch(req == null ? null : req.agentNo(), req == null ? null : req.locationNo(),
                req == null ? null : req.deviceNos(), req != null && Boolean.TRUE.equals(req.openInstallWo()),
                req == null ? null : req.expectedAt());
    }

    /** 解绑：仅已绑定未上线；回同一商家的出库池。已上线的走撤机单。 */
    @PostMapping("/api/ops/devices/{deviceNo}/unbind")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet unbind(@org.springframework.web.bind.annotation.PathVariable String deviceNo,
                          @RequestBody ai.neargo.sharehub.dev.dto.BindDtos.UnbindReq req) {
        return binds.unbind(deviceNo, req == null ? null : req.reason());
    }

    @PostMapping("/api/ops/devices/issue")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public List<Cabinet> issue(@RequestBody IssueReq req) {
        if (req == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "body");
        return inventory.dispatch(req.agentNo(), req.siteNo(), req.receiverNo(), req.cabinetNos());
    }

    /** 接收人签收（V195）：只能签给自己的 —— 权限码只是门槛，「本人 = 接收人」在服务里校验。 */
    @PostMapping("/api/ops/devices/claim")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public List<Cabinet> claim(@RequestBody ai.neargo.sharehub.dev.dto.PoolDtos.ClaimReq req) {
        return inventory.claim(req == null ? null : req.cabinetNos());
    }

    /** 出库调整（V195）：改商家 / 站点 / 接收人，只收未绑定的。 */
    @PostMapping("/api/ops/devices/issue-redirect")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public List<Cabinet> redirect(@RequestBody ai.neargo.sharehub.dev.dto.PoolDtos.RedirectReq req) {
        if (req == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "body");
        return inventory.redirect(req.cabinetNos(), req.agentNo(), req.siteNo(), req.receiverNo(), req.reason());
    }

    @PostMapping("/api/ops/devices/pool-return")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public List<Cabinet> poolReturn(@RequestBody PoolReturnReq req) {
        return inventory.returnToWarehouse(req == null ? null : req.cabinetNos(),
                req == null ? null : req.warehouseNo(), req == null ? null : req.reason());
    }

    /** 出库池页左侧的商家汇总。商家身份调同一端点，数据范围只给自己的。 */
    @GetMapping("/api/ops/inventory/issued-summary")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public List<IssuedSummary> summary(@RequestParam(required = false) String agentNo) {
        return pool.summary(agentNo);
    }
}
