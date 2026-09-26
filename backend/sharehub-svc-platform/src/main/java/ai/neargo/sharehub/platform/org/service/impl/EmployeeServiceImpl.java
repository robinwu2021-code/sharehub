package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.sharehub.auth.PermVersion;
import ai.neargo.sharehub.platform.iam.EmployeeStatus;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Employee;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.EmployeeSaveReq;
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
import java.util.LinkedHashSet;
import java.util.List;

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
    private final PermVersion permVersion;

    public EmployeeServiceImpl(IamEmployeeMapper mapper, IamEmployeeRoleMapper employeeRoleMapper,
                               IamDeptMapper deptMapper, RoleMapper roleMapper, PermVersion permVersion) {
        super(mapper);
        this.employeeRoleMapper = employeeRoleMapper;
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
        this.permVersion = permVersion;
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

    /** 保存后把角色同步进映射表。{@code roleNos == null} 表示不动角色，见 {@link #syncRoles}。 */
    @Transactional
    public Employee save(EmployeeSaveReq req) {
        IamEmployee body = new IamEmployee();
        body.setEmployeeNo(req.employeeNo());
        body.setName(req.name());
        body.setPhone(req.phone());
        body.setEmail(req.email());
        body.setDeptNo(req.deptNo());
        body.setRoleNo(req.roleNo());
        body.setStatus(req.status());
        Employee vo = super.save(body);
        IamEmployee saved = selectByKey(vo.employeeNo());
        syncRoles(saved.getEmployeeNo(), saved.getRoleNo(), req.roleNos());

        // 在线生效：状态（离职）与角色（授权）变更都要让在线会话下一次请求重建。
        // **不分状态改了还是角色改了** —— 两者都影响授权，分开判就要维护一个
        // 「哪些字段算授权相关」的清单，那种清单会漂（加个字段没人记得加进去），
        // 而多 bump 一次的代价只是所有在线会话各多做一次重建。
        //
        // 停用之所以能把人挡在门外，是 bump 与 PermissionService.rebuild 的在职回查
        // **两件事合起来**：只 bump 不回查，会话照原样重算一遍然后放行；
        // 只回查不 bump，戳没变就压根不会走到重建那一步。
        //
        // ⚠️ PermVersion 是进程内 AtomicLong：多副本部署时一个副本 bump 不会传到
        // 另一个副本，「在线生效」只对自己这台成立。切 token-store: redis 做水平扩展时
        // 必须一起解决，见 docs/technical/待办-状态机与领域缺口-执行计划.md B3。
        permVersion.bump();

        // 角色变了要重新出 VO —— 否则返回的 roleNos 还是改之前那份
        return toVO(selectByKey(vo.employeeNo()));
    }

    /**
     * 覆盖写这个人的角色集合。
     *
     * <p><b>{@code roleNos == null} 表示「不动角色」</b> —— 改个电话号码不该把角色清掉。
     * 此前这里是「删光再插主角色那一条」：一旦有了多角色，
     * 任何一次无关编辑都会把多出来的角色**静默抹掉**，而页面上看不出来。
     *
     * <p>主角色 {@code roleNo} 只要给了就并进集合：
     * 「列表显示 OPS、而授权表里没有 OPS」是最难查的那种不一致。
     */
    private void syncRoles(String employeeNo, String roleNo, List<String> roleNos) {
        if (roleNos == null) return;
        LinkedHashSet<String> want = new LinkedHashSet<>();
        if (roleNo != null && !roleNo.isBlank()) want.add(roleNo);
        roleNos.stream().filter(r -> r != null && !r.isBlank()).forEach(want::add);

        // 映射行是无软删的纯关系行，覆盖写即删旧插新（员工本体不受影响）
        employeeRoleMapper.delete(new LambdaQueryWrapper<IamEmployeeRole>()
                .eq(IamEmployeeRole::getEmployeeNo, employeeNo));
        for (String r : want) {
            IamEmployeeRole er = new IamEmployeeRole();
            er.setEmployeeNo(employeeNo);
            er.setRoleNo(r);
            employeeRoleMapper.insert(er);
        }
    }

    @Override
    protected Employee toVO(IamEmployee e) {
        return new Employee(e.getEmployeeNo(), e.getName(), maskPhone(e.getPhone()), e.getEmail(),
                e.getDeptNo(), deptName(e.getDeptNo()), e.getRoleNo(), roleName(e.getRoleNo()),
                rolesOf(e.getEmployeeNo()), e.getStatus());
    }

    /** 该员工的全部角色。出参必须带 —— 不带的话前端连「这个人有哪几个角色」都看不出来。 */
    private List<String> rolesOf(String employeeNo) {
        if (employeeNo == null || employeeNo.isBlank()) return List.of();
        return employeeRoleMapper.selectList(new LambdaQueryWrapper<IamEmployeeRole>()
                        .eq(IamEmployeeRole::getEmployeeNo, employeeNo))
                .stream().map(IamEmployeeRole::getRoleNo)
                .filter(r -> r != null && !r.isBlank()).distinct().sorted().toList();
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
