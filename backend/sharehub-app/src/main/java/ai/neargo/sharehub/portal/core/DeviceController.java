package ai.neargo.sharehub.portal.core;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.CabinetMonitorRow;
import ai.neargo.sharehub.dev.dto.DevDtos.CodeBatchRow;
import ai.neargo.sharehub.dev.dto.DevDtos.DeviceLogRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaReleaseRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaRolloutRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaTaskRow;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankCmd;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankRow;
import ai.neargo.sharehub.dev.entity.DevCodeBatch;
import ai.neargo.sharehub.dev.entity.DevOtaRelease;
import ai.neargo.sharehub.dev.entity.DevOtaRollout;
import ai.neargo.sharehub.dev.service.CodeBatchService;
import ai.neargo.sharehub.dev.service.DeviceLogService;
import ai.neargo.sharehub.dev.service.MonitorService;
import ai.neargo.sharehub.dev.service.OtaService;
import ai.neargo.sharehub.dev.service.PowerbankService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备管理端点（[api/README §3.2]）：充电宝 / 实时监控 / 设备日志 / 设备编码 / 固件 OTA。
 *
 * <p>与 {@link OpsController} 同为 {@code /api/ops} 前缀但**子路径不重叠**
 * （那边是 dashboard/cabinets/work-orders/场所域）。拆开的原因：机柜台账已在 OpsController 稳定服役，
 * 本片是设备域后续菜单叶的落点，混进去会让那个类继续膨胀。
 *
 * <p>控制器只做路由 + 鉴权 + 调 service；写操作遵循 {@code POST /{collection}} 建、
 * {@code POST /{collection}/{no}} 改，**全站无 DELETE**。
 */
@RestController
@RequestMapping("/api/ops")
public class DeviceController {

    private final PowerbankService powerbankService;
    private final MonitorService monitorService;
    private final DeviceLogService deviceLogService;
    private final CodeBatchService codeBatchService;
    private final OtaService otaService;

    public DeviceController(PowerbankService powerbankService, MonitorService monitorService,
                            DeviceLogService deviceLogService, CodeBatchService codeBatchService,
                            OtaService otaService) {
        this.powerbankService = powerbankService;
        this.monitorService = monitorService;
        this.deviceLogService = deviceLogService;
        this.codeBatchService = codeBatchService;
        this.otaService = otaService;
    }

    // —— 充电宝管理（菜单叶：设备管理 › 充电宝管理）——

    @GetMapping("/powerbanks")
    @PreAuthorize("@perm.can('device:powerbank:read')")
    public PageResult<PowerbankRow> powerbanks(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String cabinetNo) {
        return powerbankService.page(page, size, keyword, status, cabinetNo);
    }

    @PostMapping("/powerbanks")
    @PreAuthorize("@perm.can('device:powerbank:update')")
    public PowerbankRow createPowerbank(@RequestBody PowerbankCmd body) {
        return powerbankService.create(body);
    }

    /** 状态变更（报废/丢失/投放…）与属性更新；状态迁移由 {@code PowerbankStateMachine} 把关。 */
    @PostMapping("/powerbanks/{powerbankNo}")
    @PreAuthorize("@perm.can('device:powerbank:update')")
    public PowerbankRow updatePowerbank(@PathVariable String powerbankNo, @RequestBody PowerbankCmd body) {
        return powerbankService.update(powerbankNo, body); // 路径为准，忽略 body 里的键
    }

    // —— 实时监控（菜单叶：设备管理 › 实时监控）——

    @GetMapping("/cabinet-monitor")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public PageResult<CabinetMonitorRow> cabinetMonitor(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Boolean online) {
        return monitorService.monitor(page, size, keyword, online);
    }

    // —— 设备日志（菜单叶：设备管理 › 设备日志）——

    /** 双流日志（{@code stream=COMMAND|REPORT} + {@code from/to}）。gw 域落地前返空页，见 DeviceLogServiceImpl。 */
    @GetMapping("/device-logs")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public PageResult<DeviceLogRow> deviceLogs(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String cabinetNo,
                                               @RequestParam(required = false) String stream,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        return deviceLogService.page(page, size, cabinetNo, stream, from, to);
    }

    // —— 设备编码（菜单叶：设备管理 › 设备编码）——

    @GetMapping("/device-code-batches")
    @PreAuthorize("@perm.can('device:cabinet:read')")
    public PageResult<CodeBatchRow> codeBatches(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String vendorCode,
                                                @RequestParam(required = false) String codeType,
                                                @RequestParam(required = false) String status) {
        return codeBatchService.page(page, size, keyword,
                Map.of("vendorCode", nz(vendorCode), "codeType", nz(codeType), "status", nz(status)));
    }

    @PostMapping("/device-code-batches")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public CodeBatchRow createCodeBatch(@RequestBody DevCodeBatch body) {
        return codeBatchService.save(body);
    }

    @PostMapping("/device-code-batches/{batchNo}")
    @PreAuthorize("@perm.can('device:cabinet:update')")
    public CodeBatchRow updateCodeBatch(@PathVariable String batchNo, @RequestBody DevCodeBatch body) {
        body.setBatchNo(batchNo); // 路径为准，防越权改他批次
        return codeBatchService.save(body);
    }

    // —— 固件 OTA（菜单叶：设备管理 › 固件 OTA）——

    @GetMapping("/ota-rollouts")
    @PreAuthorize("@perm.can('device:ota:read')")
    public PageResult<OtaRolloutRow> otaRollouts(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String releaseNo,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String strategy) {
        return otaService.rollouts(page, size, keyword, releaseNo, status, strategy);
    }

    @PostMapping("/ota-rollouts")
    @PreAuthorize("@perm.can('device:ota:manage')")
    public OtaRolloutRow createOtaRollout(@RequestBody DevOtaRollout body) {
        return otaService.saveRollout(body);
    }

    /** 更新投放（灰度比例调整、回滚 {@code status=ROLLBACK}）。 */
    @PostMapping("/ota-rollouts/{rolloutNo}")
    @PreAuthorize("@perm.can('device:ota:manage')")
    public OtaRolloutRow updateOtaRollout(@PathVariable String rolloutNo, @RequestBody DevOtaRollout body) {
        body.setRolloutNo(rolloutNo);
        return otaService.saveRollout(body);
    }

    /**
     * 投放的逐设备任务明细（{@code dev_ota_task} 下钻）。
     * <p>api/README §3.2 未列本端点，但 {@code dev_ota_task} 没有它就无处可读 —— 见交付报告的规格缺漏。
     */
    @GetMapping("/ota-rollouts/{rolloutNo}/tasks")
    @PreAuthorize("@perm.can('device:ota:read')")
    public List<OtaTaskRow> otaTasks(@PathVariable String rolloutNo) {
        return otaService.tasks(rolloutNo);
    }

    @GetMapping("/ota-releases")
    @PreAuthorize("@perm.can('device:ota:manage')")
    public PageResult<OtaReleaseRow> otaReleases(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String fwType,
                                                 @RequestParam(required = false) String vendorCode,
                                                 @RequestParam(required = false) String status) {
        return otaService.releases(page, size, keyword, fwType, vendorCode, status);
    }

    @PostMapping("/ota-releases")
    @PreAuthorize("@perm.can('device:ota:manage')")
    public OtaReleaseRow createOtaRelease(@RequestBody DevOtaRelease body) {
        return otaService.saveRelease(body);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 归档Powerbank。**不是删除** —— 行仍在，勾「显示已归档」可见，可 unarchive 恢复。
     *
     * <p>归档语义统一在 {@code AbstractCrudService}：盖 {@code archivedAt} 时间戳。
     * 时间戳而非布尔位，因为「什么时候归档的」本身是审计信息。
     */
    @PostMapping("/powerbanks/{no}/archive")
    @PreAuthorize("@perm.can('device:powerbank:update')")
    public Object archivePowerbank(@PathVariable String no) {
        return powerbankService.archive(no);
    }

    /** 取消归档Powerbank：清空时间戳，回到默认列表。 */
    @PostMapping("/powerbanks/{no}/unarchive")
    @PreAuthorize("@perm.can('device:powerbank:update')")
    public Object unarchivePowerbank(@PathVariable String no) {
        return powerbankService.unarchive(no);
    }
}
