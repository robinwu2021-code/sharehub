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

    public DevicePoolController(CabinetPoolService pool, ai.neargo.sharehub.dev.service.CabinetBindService binds) {
        this.pool = pool;
        this.binds = binds;
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
        return pool.issue(req == null ? null : req.agentNo(), req == null ? null : req.cabinetNos());
    }

    @PostMapping("/api/ops/devices/pool-return")
    @PreAuthorize("@perm.can('device:inventory:transfer')")
    public List<Cabinet> poolReturn(@RequestBody PoolReturnReq req) {
        return pool.returnToWarehouse(req == null ? null : req.cabinetNos(),
                req == null ? null : req.warehouseNo(), req == null ? null : req.reason());
    }

    /** 出库池页左侧的商家汇总。商家身份调同一端点，数据范围只给自己的。 */
    @GetMapping("/api/ops/inventory/issued-summary")
    @PreAuthorize("@perm.can('device:inventory:read')")
    public List<IssuedSummary> summary(@RequestParam(required = false) String agentNo) {
        return pool.summary(agentNo);
    }
}
