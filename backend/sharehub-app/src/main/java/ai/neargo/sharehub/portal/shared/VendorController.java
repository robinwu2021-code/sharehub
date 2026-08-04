package ai.neargo.sharehub.portal.shared;

import ai.neargo.sharehub.gw.dto.GwDtos.VendorProbeResult;
import ai.neargo.sharehub.gw.dto.GwDtos.VendorVO;
import ai.neargo.sharehub.gw.service.VendorService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * access-gateway 供应商接入端点（对齐 docs/api §四 与 ops-web {@code http.ts}）：
 * 供应商列表 / 接入配置 upsert / 连通性探测。落 {@code gw_vendor}/{@code gw_vendor_config}，
 * SeedData 骨架已退役。
 *
 * <p>TODO：此归属独立 access-gateway 进程（ADR-001 双栈网关）；单体形态由 app 代管，后续裂解。
 */
@RestController
@RequestMapping("/internal/gw/vendors")
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @GetMapping
    @PreAuthorize("@perm.can('device:vendor:read')")
    public List<VendorVO> vendors() {
        return vendorService.list();
    }

    @PostMapping("/{vendorCode}/config")
    @PreAuthorize("@perm.can('device:vendor:config')")
    public VendorVO saveConfig(@PathVariable String vendorCode, @RequestBody Map<String, Object> in) {
        return vendorService.saveConfig(vendorCode, in);
    }

    /** 连通性探测（S7）：「配置能存」≠「对得上」，填错要在配置页当场知道。 */
    @PostMapping("/{vendorCode}/test")
    @PreAuthorize("@perm.can('device:vendor:config')")
    public VendorProbeResult test(@PathVariable String vendorCode) {
        return vendorService.probe(vendorCode);
    }
}
