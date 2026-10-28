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

    public DevicePoolController(CabinetPoolService pool) {
        this.pool = pool;
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
