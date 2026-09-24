package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.DataScopeEntry;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.DataScopeReq;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Department;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.StaffPerformance;
import ai.neargo.sharehub.platform.org.entity.IamDept;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.service.DataScopeService;
import ai.neargo.sharehub.platform.org.service.DepartmentService;
import ai.neargo.sharehub.platform.org.service.EmployeeService;
import ai.neargo.sharehub.platform.org.service.StaffPerformanceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 组织与员工（[api/README §7.1]）：员工 · 组织架构 · 数据权限 · 绩效报表。
 *
 * <p><b>路径分工</b>：{@code GET /api/platform/employees} 与 {@code /audit-logs} 已由既有的
 * {@link PlatformController}（内存种子）占用，本控制器**只补它没有的写端点与新集合**——
 * 同一路径重复映射会让 Spring 启动直接失败。待种子退场后，读端点再迁到这里。
 *
 * <p>写操作形态遵循 [api/README §1.5]：{@code POST /{collection}} 建、
 * {@code POST /{collection}/{no}} 改，**全站无 DELETE**（离职置 {@code status=LEFT}）。
 * 唯一的 {@code PUT} 是数据权限 —— 它是「整体覆盖某主体的范围」的语义，不是新建也不是部分更新。
 */
@RestController
@RequestMapping("/api/platform")
public class OrgController {

    private final EmployeeService employeeService;
    private final DepartmentService departmentService;
    private final DataScopeService dataScopeService;
    private final StaffPerformanceService staffPerformanceService;

    public OrgController(EmployeeService employeeService, DepartmentService departmentService,
                         DataScopeService dataScopeService, StaffPerformanceService staffPerformanceService) {
        this.employeeService = employeeService;
        this.departmentService = departmentService;
        this.dataScopeService = dataScopeService;
        this.staffPerformanceService = staffPerformanceService;
    }

    // —— 员工（菜单叶：员工与权限 › 员工）——
    // GET /employees 见 PlatformController（已占用）

    @PostMapping("/employees")
    @PreAuthorize("@perm.can('org:employee:create')")
    public Employee createEmployee(@RequestBody IamEmployee body) {
        return employeeService.save(body);
    }

    @PostMapping("/employees/{employeeNo}")
    // 改用 :update —— 清单 §员工「增/改/删」把 create/update/delete 分成三个码，
    // 此前改员工也判 :create。新增那个端点保持 :create 不动。
    // （两码当前都只有 ADMIN 持有，访问面不变。）
    @PreAuthorize("@perm.can('org:employee:update')")
    public Employee updateEmployee(@PathVariable String employeeNo, @RequestBody IamEmployee body) {
        body.setEmployeeNo(employeeNo); // 路径为准，忽略 body 里的键，防越权改他人
        return employeeService.save(body);
    }

    // —— 组织架构（菜单叶：员工与权限 › 组织架构）——

    @GetMapping("/departments")
    @PreAuthorize("@perm.can('org:dept:read')")
    public PageResult<Department> departments(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String parentNo,
                                              @RequestParam(required = false) String status) {
        return departmentService.page(page, size, keyword,
                Map.of("parentNo", nz(parentNo), "status", nz(status)));
    }

    @PostMapping("/departments")
    @PreAuthorize("@perm.can('org:dept:create')")
    public Department createDepartment(@RequestBody IamDept body) {
        return departmentService.save(body);
    }

    @PostMapping("/departments/{deptNo}")
    @PreAuthorize("@perm.can('org:dept:create')")
    public Department updateDepartment(@PathVariable String deptNo, @RequestBody IamDept body) {
        body.setDeptNo(deptNo);
        return departmentService.save(body);
    }

    // —— 数据权限（页内抽屉，补 G7 缺口）——

    @GetMapping("/data-scopes/{subjectType}/{subjectNo}")
    @PreAuthorize("@perm.can('org:role:read')")
    public DataScopeEntry dataScope(@PathVariable String subjectType, @PathVariable String subjectNo) {
        return dataScopeService.get(subjectType, subjectNo);
    }

    /** 保存数据权限（整体覆盖）。前端抽屉此前只 invalidate 不落库，这里是它的对端。 */
    @PutMapping("/data-scopes/{subjectType}/{subjectNo}")
    @PreAuthorize("@perm.can('org:role:update')")
    public DataScopeEntry saveDataScope(@PathVariable String subjectType, @PathVariable String subjectNo,
                                        @RequestBody DataScopeReq body) {
        return dataScopeService.save(subjectType, subjectNo, body.scopeType(), body.scopeRefs());
    }

    // —— 绩效报表（菜单叶：员工与权限 › 绩效报表）——

    @GetMapping("/staff-performance")
    @PreAuthorize("@perm.can('org:performance:read')")
    public PageResult<StaffPerformance> staffPerformance(@RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String period,
                                                         @RequestParam(required = false) String role) {
        return staffPerformanceService.page(page, size, keyword, period, role);
    }

    /** {@code Map.of} 不接受 null，统一转空串；空串在 CRUD 基类里等价于「不过滤」。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
