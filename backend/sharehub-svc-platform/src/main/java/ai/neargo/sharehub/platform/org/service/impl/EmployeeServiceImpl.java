package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.sharehub.platform.iam.EmployeeStatus;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.entity.IamDept;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.entity.IamEmployeeRole;
import ai.neargo.sharehub.platform.org.mapper.IamDeptMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeRoleMapper;
import ai.neargo.sharehub.platform.org.service.EmployeeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 员工实现。
 *
 * <p>CRUD 主体来自 {@link AbstractCrudService}，本类只额外做两件事：
 * <ol>
 *   <li><b>保存后同步主角色</b>到 {@code iam_employee_role} —— {@code iam_employee.role_no} 是列表展示用的
 *       主角色，真正的授权关系在映射表；两处不同步会导致「页面显示 OPS、实际权限还是旧角色」。</li>
 *   <li><b>出参掩码手机号</b>（[api/README §1.6]）。表里存的本就是掩码值，这里再掩一次是兜底：
 *       历史数据或导入数据可能带明文，掩码函数对已掩码值幂等。</li>
 * </ol>
 */
@Service
public class EmployeeServiceImpl extends AbstractCrudService<IamEmployee, Employee> implements EmployeeService {

    private final IamEmployeeRoleMapper employeeRoleMapper;
    private final IamDeptMapper deptMapper;
    private final RoleMapper roleMapper;

    public EmployeeServiceImpl(IamEmployeeMapper mapper, IamEmployeeRoleMapper employeeRoleMapper,
                               IamDeptMapper deptMapper, RoleMapper roleMapper) {
        super(mapper);
        this.employeeRoleMapper = employeeRoleMapper;
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    protected String keyColumn() {
        return "employee_no";
    }

    @Override
    protected String keyOf(IamEmployee e) {
        return e.getEmployeeNo();
    }

    @Override
    protected void setKey(IamEmployee e, String no) {
        e.setEmployeeNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.EMPLOYEE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"employee_no", "name", "phone", "email"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status", "deptNo", "roleNo"};
    }

    @Override
    protected String orderColumn() {
        return "employee_no";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(IamEmployee e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(EmployeeStatus.ACTIVE.name());
        e.setPhone(maskPhone(e.getPhone()));
    }

    @Override
    protected void beforeUpdate(IamEmployee e, IamEmployee current) {
        e.setPhone(maskPhone(e.getPhone()));
        // 空手机不视为"清空"：编辑表单回填的是掩码值，漏传时保留原值，避免误擦
        if (e.getPhone() == null) e.setPhone(current.getPhone());
    }

    /** 保存后把主角色同步进映射表（覆盖写：一人一主角色，多角色由后续授权页扩展）。 */
    @Override
    @Transactional
    public Employee save(IamEmployee body) {
        Employee vo = super.save(body);
        IamEmployee saved = selectByKey(vo.employeeNo());
        syncPrimaryRole(saved.getEmployeeNo(), saved.getRoleNo());
        return vo;
    }

    private void syncPrimaryRole(String employeeNo, String roleNo) {
        if (roleNo == null || roleNo.isBlank()) return;
        boolean exists = employeeRoleMapper.exists(new LambdaQueryWrapper<IamEmployeeRole>()
                .eq(IamEmployeeRole::getEmployeeNo, employeeNo)
                .eq(IamEmployeeRole::getRoleNo, roleNo));
        if (exists) return;
        // 映射行是无软删的纯关系行，改绑定即删旧插新（员工本体不受影响）
        employeeRoleMapper.delete(new LambdaQueryWrapper<IamEmployeeRole>()
                .eq(IamEmployeeRole::getEmployeeNo, employeeNo));
        IamEmployeeRole er = new IamEmployeeRole();
        er.setEmployeeNo(employeeNo);
        er.setRoleNo(roleNo);
        employeeRoleMapper.insert(er);
    }

    @Override
    protected Employee toVO(IamEmployee e) {
        return new Employee(e.getEmployeeNo(), e.getName(), maskPhone(e.getPhone()), e.getEmail(),
                deptName(e.getDeptNo()), roleName(e.getRoleNo()), e.getStatus());
    }

    private String deptName(String deptNo) {
        if (deptNo == null || deptNo.isBlank()) return null;
        IamDept d = deptMapper.selectOne(new LambdaQueryWrapper<IamDept>()
                .eq(IamDept::getDeptNo, deptNo).last("limit 1"));
        return d == null ? null : d.getName();
    }

    private String roleName(String roleNo) {
        if (roleNo == null || roleNo.isBlank()) return null;
        IamRole r = roleMapper.selectOne(new LambdaQueryWrapper<IamRole>()
                .eq(IamRole::getRoleNo, roleNo).last("limit 1"));
        return r == null ? null : r.getName();
    }

    /** 手机掩码：留前 6 后 2（[api/README §1.6]）。对已含 {@code *} 的值原样返回，保证幂等。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank() || phone.indexOf('*') >= 0) return phone;
        if (phone.length() <= 8) return phone.charAt(0) + "*".repeat(Math.max(1, phone.length() - 1));
        return phone.substring(0, 6) + "*".repeat(phone.length() - 8) + phone.substring(phone.length() - 2);
    }
}
