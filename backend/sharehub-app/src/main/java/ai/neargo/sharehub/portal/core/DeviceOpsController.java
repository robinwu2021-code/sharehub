package ai.neargo.sharehub.portal.core;

import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.Protection;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.ProtectionReq;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.ReasonReq;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.TrialRent;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import ai.neargo.sharehub.dev.service.ProtectionService;
import ai.neargo.sharehub.dev.service.TrialRentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机柜运维动作（TDD-运营核心流程/04 §五）。新端点直接用目标资源名 {@code /api/ops/devices/{deviceNo}}；
 * 过渡期 deviceNo = cabinetNo，权限码仍为 {@code device:cabinet:*}（设备改名 D1 同批改 {@code device:asset:*}）。
 * 建档 / 编辑仍在 OpsController 的 cabinets 端点，但编辑不再改状态。
 */
@RestController
@RequestMapping("/api/ops/devices")
public class DeviceOpsController {

    private final CabinetLifecycleService lifecycle;
    private final TrialRentService trials;
    private final ProtectionService protections;

    public DeviceOpsController(CabinetLifecycleService lifecycle, TrialRentService trials, ProtectionService protections) {
        this.lifecycle = lifecycle;
        this.trials = trials;
        this.protections = protections;
    }

    @GetMapping("/{deviceNo}/go-live-gate")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public Checklist goLiveGate(@PathVariable String deviceNo) {
        return lifecycle.goLiveGate(deviceNo);
    }

    /** 门禁全过才上线；站点若在筹备中随之转营业。 */
    @PostMapping("/{deviceNo}/go-live")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet goLive(@PathVariable String deviceNo) {
        return lifecycle.goLive(deviceNo);
    }

    @PostMapping("/{deviceNo}/mark-fault")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet markFault(@PathVariable String deviceNo, @RequestBody(required = false) ReasonReq body) {
        return lifecycle.markFault(deviceNo, body == null ? null : body.reason());
    }

    @PostMapping("/{deviceNo}/repair")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet repair(@PathVariable String deviceNo) {
        return lifecycle.repair(deviceNo);
    }

    @PostMapping("/{deviceNo}/undeploy")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet undeploy(@PathVariable String deviceNo, @RequestBody(required = false) ReasonReq body) {
        return lifecycle.undeploy(deviceNo, body == null ? null : body.reason());
    }

    @PostMapping("/{deviceNo}/retire")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Cabinet retire(@PathVariable String deviceNo, @RequestBody(required = false) ReasonReq body) {
        return lifecycle.retire(deviceNo, body == null ? null : body.reason());
    }

    @GetMapping("/{deviceNo}/trial-rents")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public List<TrialRent> trialRents(@PathVariable String deviceNo) {
        return trials.list(deviceNo);
    }

    /** 发起试借还：下发弹出指令（异步），结果以设备信号回推。 */
    @PostMapping("/{deviceNo}/trial-rents")
    @PreAuthorize("@perm.can('device:command:send')")
    public TrialRent startTrialRent(@PathVariable String deviceNo) {
        return trials.start(deviceNo);
    }

    @GetMapping("/{deviceNo}/protections")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public List<Protection> protections(@PathVariable String deviceNo,
                                        @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return protections.list(deviceNo, activeOnly);
    }

    /** 人工停借 / 禁仓。 */
    @PostMapping("/{deviceNo}/protections")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Protection applyProtection(@PathVariable String deviceNo, @RequestBody ProtectionReq body) {
        return protections.applyManual(deviceNo, body.slotIndex(), body.action(), body.reason());
    }

    /** 只能解除人工持有的保护；信号 / 告警持有的随恢复自动解除。 */
    @PostMapping("/protections/{protectionNo}/release")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public Protection releaseProtection(@PathVariable String protectionNo, @RequestBody(required = false) ReasonReq body) {
        return protections.releaseManual(protectionNo, body == null ? null : body.reason());
    }
}
