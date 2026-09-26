package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.entity.IamEmployeeRole;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工 → 角色（{@code iam_employee_role}）。
 *
 * <h2>这一段此前是断的</h2>
 * 表和同步代码都在，但生产 5 个员工是种子直灌的、没走过 service，
 * 于是这张表 <b>0 行</b>（V78 已回填）。而登录时的角色来自**请求或配置**，
 * 与员工档案毫无关系 —— 「按用户动态展示菜单」里的「用户」实际上不存在。
 *
 * <h2>边界：这里只解决「授权」，不解决「认证」</h2>
 * 生产的登录仍是「用户名必须是 admin + 一个配置里的共享口令」，
 * 员工凭据库（{@code pb_auth}）<b>至今只存在于注释里</b> ——
 * 没有凭据表、没有 password_hash。所以本类解决的是
 * 「<b>已经确定是谁之后</b>，他的角色从哪来」，
 * 而「怎么确定是谁」要等真实员工登录那件事（v4/06 A3）。
 *
 * <p>在那之前：认得出员工就用表里的角色，认不出就沿用今天的行为。
 * <b>不因为认不出就拒绝登录</b> —— 那会让生产唯一能用的账号登不进去。
 */
@Service
public class EmployeeRoles {

    private final IamEmployeeMapper employeeMapper;
    private final IamEmployeeRoleMapper employeeRoleMapper;

    public EmployeeRoles(IamEmployeeMapper employeeMapper, IamEmployeeRoleMapper employeeRoleMapper) {
        this.employeeMapper = employeeMapper;
        this.employeeRoleMapper = employeeRoleMapper;
    }

    /**
     * 这个登录名**是员工档案里的人、但已经不在职**。
     *
     * <p>{@link #rolesOf} 对「不是员工」与「已离职」都回空表 —— 授权口径上两者等价，
     * 认证口径上不等价：前者今天是 {@code admin} 这种不在档案里的运维账号（必须放行），
     * 后者是离职的人拿着还没停用的凭据（必须拒）。<b>不区分就会让离职的人登进来</b>。
     */
    public boolean isLeftEmployee(String loginName) {
        if (loginName == null || loginName.isBlank()) return false;
        IamEmployee e = employeeMapper.selectOne(new LambdaQueryWrapper<IamEmployee>()
                .eq(IamEmployee::getEmployeeNo, loginName).last("limit 1"));
        return e != null && !EmployeeStatus.ACTIVE.is(e.getStatus());
    }

    /**
     * 这个登录名对应员工的全部角色；不是员工、或已离职、或没配角色 → 空表。
     *
     * <p><b>离职即失去全部角色</b>，不是降级成某个默认角色：
     * 「停用了但还剩点权限」比「停用了还全须全尾」更难发现。
     *
     * @param loginName 今天等于 {@code employee_no}；真实员工登录落地后改为凭据上的登录名
     */
    public List<String> rolesOf(String loginName) {
        if (loginName == null || loginName.isBlank()) return List.of();
        IamEmployee e = employeeMapper.selectOne(new LambdaQueryWrapper<IamEmployee>()
                .eq(IamEmployee::getEmployeeNo, loginName).last("limit 1"));
        if (e == null || !EmployeeStatus.ACTIVE.is(e.getStatus())) return List.of();
        return employeeRoleMapper.selectList(new LambdaQueryWrapper<IamEmployeeRole>()
                        .eq(IamEmployeeRole::getEmployeeNo, e.getEmployeeNo()))
                .stream().map(IamEmployeeRole::getRoleNo)
                .filter(r -> r != null && !r.isBlank()).distinct().toList();
    }
}
