package ai.neargo.powerbank.portal.ops;

import ai.neargo.powerbank.common.Kw;
import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.common.Pages;
import ai.neargo.powerbank.dto.Dto.*;
import ai.neargo.powerbank.seed.SeedData;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * platform 域运营端端点（对齐 docs/api §二 与 ops-web {@code http.ts}）：
 * 员工 / 角色（含数据范围）/ 审计日志。租户管理为后端兼容层，运营端不体现（ADR-011）。
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final SeedData db;

    public PlatformController(SeedData db) {
        this.db = db;
    }

    @GetMapping("/employees")
    public PageResult<Employee> employees(@RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String keyword) {
        List<Employee> rows = db.employees().stream().filter(e -> Kw.hit(keyword, e.name())).toList();
        return Pages.of(rows, page, size);
    }

    @GetMapping("/roles")
    public List<RoleRow> roles() {
        return db.roles();
    }

    @GetMapping("/audit-logs")
    public PageResult<AuditEntry> auditLogs(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword) {
        List<AuditEntry> rows = db.audits().stream()
                .filter(a -> Kw.hit(keyword, a.actor(), a.action(), a.target())).toList();
        return Pages.of(rows, page, size);
    }
}
