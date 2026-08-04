package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.Department;
import ai.neargo.sharehub.platform.org.entity.IamDept;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.mapper.IamDeptMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.sharehub.platform.org.service.DepartmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 组织架构实现。CRUD 之外只有两条规则，都是为了「树」这件事：
 *
 * <ol>
 *   <li><b>{@code path} 由服务端算，不信入参</b>：{@code path = 上级的 path + 自己的 deptNo + "/"}。
 *       调用方只需给 {@code parentNo}。算错的 path 会让子树查询静默漏数据，所以不接受前端传值。</li>
 *   <li><b>{@code memberCount} 实时聚合</b>（[db-design §1.4]「计数列不是列」）——
 *       落成列必然与 {@code iam_employee} 漂移：员工调岗、离职、批量导入都会绕过计数维护。</li>
 * </ol>
 *
 * <p>另有一条防呆：不允许把自己设为自己的上级（直接自引用会造出无限递归的树）。
 */
@Service
public class DepartmentServiceImpl extends AbstractCrudService<IamDept, Department> implements DepartmentService {

    private final IamEmployeeMapper employeeMapper;

    public DepartmentServiceImpl(IamDeptMapper mapper, IamEmployeeMapper employeeMapper) {
        super(mapper);
        this.employeeMapper = employeeMapper;
    }

    @Override
    protected String keyColumn() {
        return "dept_no";
    }

    @Override
    protected String keyOf(IamDept e) {
        return e.getDeptNo();
    }

    @Override
    protected void setKey(IamDept e, String no) {
        e.setDeptNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.DEPARTMENT;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"dept_no", "name", "name_en", "leader_name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"parentNo", "status"};
    }

    @Override
    protected String orderColumn() {
        return "sort";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(IamDept e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("ACTIVE");
        if (e.getSort() == null) e.setSort(0);
        e.setPath(buildPath(e));
    }

    @Override
    protected void beforeUpdate(IamDept e, IamDept current) {
        if (e.getDeptNo() != null && e.getDeptNo().equals(e.getParentNo())) {
            throw new IllegalArgumentException("部门不能以自己为上级: " + e.getDeptNo());
        }
        if (e.getSort() == null) e.setSort(current.getSort());
        if (e.getStatus() == null) e.setStatus(current.getStatus());
        e.setPath(buildPath(e));
    }

    /** {@code /D1/D3/}：上级路径 + 自身。上级不存在时按顶级处理（逻辑外键，不做级联约束）。 */
    private String buildPath(IamDept e) {
        String parentPath = "/";
        String parentNo = e.getParentNo();
        if (parentNo != null && !parentNo.isBlank()) {
            IamDept parent = selectByKey(parentNo);
            if (parent != null && parent.getPath() != null && !parent.getPath().isBlank()) {
                parentPath = parent.getPath();
            } else if (parent != null) {
                parentPath = "/" + parent.getDeptNo() + "/";
            }
        }
        return parentPath + e.getDeptNo() + "/";
    }

    @Override
    protected Department toVO(IamDept e) {
        return new Department(e.getDeptNo(), e.getName(), parentName(e.getParentNo()),
                memberCount(e.getDeptNo()), e.getLeaderName(), e.getPath(), e.getSort(), e.getStatus());
    }

    private String parentName(String parentNo) {
        if (parentNo == null || parentNo.isBlank()) return null;
        IamDept p = selectByKey(parentNo);
        return p == null ? null : p.getName();
    }

    /** 在岗成员数（LEFT 不计）；离职员工留行不留编制。 */
    private long memberCount(String deptNo) {
        return employeeMapper.selectCount(new LambdaQueryWrapper<IamEmployee>()
                .eq(IamEmployee::getDeptNo, deptNo)
                .eq(IamEmployee::getStatus, "ACTIVE"));
    }
}
