package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditDetail;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditLogEntry;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.RoleRowVO;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import ai.neargo.sharehub.platform.org.service.EmployeeService;
import ai.neargo.sharehub.platform.org.service.RoleQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * platform 域运营端端点（对齐 docs/api §二 与 ops-web {@code http.ts}）：
 * 员工 / 角色（列表·归档）/ 审计日志。SeedData 骨架已退役，全部走 iam_* 真表。
 *
 * <p>角色的**授权读写**（权限集）在 {@code IamAdminController}（{@code /api/platform/iam/**}），
 * 本类只管角色行的列表与归档 —— 两类操作权限码不同（org:role:read vs org:role:assign）。
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final EmployeeService employeeService;
    private final RoleQueryService roleQueryService;
    private final AuditLogService auditLogService;

    public PlatformController(EmployeeService employeeService, RoleQueryService roleQueryService,
                              AuditLogService auditLogService) {
        this.employeeService = employeeService;
        this.roleQueryService = roleQueryService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/employees")
    @PreAuthorize("@perm.can('org:employee:read')")
    public PageResult<Employee> employees(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String keyword) {
        return employeeService.page(page, size, keyword, Map.of());
    }

    @GetMapping("/roles")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<RoleRowVO> roles(@RequestParam(required = false) String showArchived) {
        return roleQueryService.list("1".equals(showArchived) || "true".equals(showArchived));
    }

    /** 新建 / 修改角色。此前只有列表与归档 —— 前端「新增/编辑角色」在真后端下必 404。 */
    @PostMapping("/roles")
    @PreAuthorize("@perm.can('org:role:update')")
    public RoleRowVO createRole(@RequestBody RoleRowVO body) {
        return roleQueryService.save(null, body);   // 新建一律服务端取号
    }

    @PostMapping("/roles/{roleNo}")
    @PreAuthorize("@perm.can('org:role:update')")
    public RoleRowVO updateRole(@PathVariable String roleNo, @RequestBody RoleRowVO body) {
        return roleQueryService.save(roleNo, body); // 路径为准，防越权改他人角色
    }

    /** 归档角色（内置角色服务端拒绝）。**不是删除**，可 unarchive 恢复。 */
    @PostMapping("/roles/{roleNo}/archive")
    @PreAuthorize("@perm.can('org:role:update')")
    public RoleRowVO archiveRole(@PathVariable String roleNo) {
        return roleQueryService.archive(roleNo);
    }

    @PostMapping("/roles/{roleNo}/unarchive")
    @PreAuthorize("@perm.can('org:role:update')")
    public RoleRowVO unarchiveRole(@PathVariable String roleNo) {
        return roleQueryService.unarchive(roleNo);
    }

    /**
     * 操作审计列表（WORM 只增）。与详情同码 {@code org:audit:read} ——
     * 曾出现「详情比列表还严」的倒挂，此处保持一致。
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("@perm.can('org:audit:read')")
    public PageResult<AuditLogEntry> auditLogs(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) String keyword) {
        return auditLogService.page(page, size, keyword, null, null, null);
    }

    /** 审计详情（列表行点开抽屉）。与列表同源同码。 */
    @GetMapping("/audit-logs/{id}")
    @PreAuthorize("@perm.can('org:audit:read')")
    public AuditDetail auditLogDetail(@PathVariable String id) {
        AuditDetail d = auditLogService.detail(id);
        if (d == null) throw new IllegalArgumentException("审计记录不存在: " + id);
        return d;
    }
}
