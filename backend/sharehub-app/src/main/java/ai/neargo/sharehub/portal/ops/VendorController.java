package ai.neargo.powerbank.portal.ops;

import ai.neargo.powerbank.dto.Dto.Vendor;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * access-gateway 供应商接入端点（对齐 docs/api §四 与 ops-web {@code http.ts}）：
 * 供应商列表 / 接入配置 upsert。
 *
 * <p>TODO：此归属独立 access-gateway 进程（ADR-001 双栈网关）；骨架阶段由 app 代管，后续裂解。
 */
@RestController
@RequestMapping("/internal/gw/vendors")
public class VendorController {

    private final SeedData db;

    public VendorController(SeedData db) {
        this.db = db;
    }

    @GetMapping
    public List<Vendor> vendors() {
        return db.vendors();
    }

    @PostMapping("/{vendorCode}/config")
    @PreAuthorize("@perm.can('device:vendor:config')")
    public Vendor saveConfig(@PathVariable String vendorCode, @RequestBody Vendor in) {
        Vendor stored = new Vendor(vendorCode, in.name(), in.accessMode(),
                in.status() == null ? "ENABLED" : in.status(), in.apiBase(), in.deviceCount());
        boolean existed = db.vendors().stream().anyMatch(v -> v.vendorCode().equals(vendorCode));
        if (existed) {
            db.replaceFirst(db.vendors(), v -> v.vendorCode().equals(vendorCode), stored);
        } else {
            db.vendors().add(stored);
        }
        return stored;
    }
}
