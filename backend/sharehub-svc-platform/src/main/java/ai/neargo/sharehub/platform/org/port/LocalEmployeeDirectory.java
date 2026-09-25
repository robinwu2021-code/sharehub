package ai.neargo.sharehub.platform.org.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.EmployeeBrief;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.platform.iam.EmployeeStatus;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers;
import ai.neargo.sharehub.platform.org.entity.IamEmployee;
import ai.neargo.sharehub.platform.org.entity.IamEmployeeRole;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeMapper;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link EmployeeDirectoryPort} 的本地实现。 */
@Component
public class LocalEmployeeDirectory implements EmployeeDirectoryPort {

    private final IamEmployeeMapper employees;
    private final IamEmployeeRoleMapper employeeRoles;
    private final IamMappers.RoleMapper roles;
    private final ai.neargo.sharehub.platform.org.service.DataScopeService scopes;

    public LocalEmployeeDirectory(IamEmployeeMapper employees, IamEmployeeRoleMapper employeeRoles, IamMappers.RoleMapper roles,
                                  ai.neargo.sharehub.platform.org.service.DataScopeService scopes) {
        this.scopes = scopes;
        this.employees = employees;
        this.employeeRoles = employeeRoles;
        this.roles = roles;
    }

    @Override
    public Map<String, EmployeeBrief> briefsOf(Collection<String> employeeNos) {
        if (employeeNos == null || employeeNos.isEmpty()) return Map.of();
        Map<String, EmployeeBrief> out = new LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() -> employees.selectList(new LambdaQueryWrapper<IamEmployee>()
                .in(IamEmployee::getEmployeeNo, employeeNos)))
                .forEach(e -> out.putIfAbsent(e.getEmployeeNo(), new EmployeeBrief(e.getEmployeeNo(), e.getName(), e.getStatus(), null)));
        return out;
    }

    @Override
    public List<EmployeeBrief> activeByRoles(Set<String> roleCodes, int limit) {
        // 员工上的 role_no 两种写法都有：角色号（R2）与角色码（OPS，iam_role 注释写的「role_no=code，MVP 简化」）。
        // 实测种子员工存的是码 —— 只按角色号匹配会一个人都找不到，派单通知 / 告警升级就静默落空。两种都认。
        Map<String, String> roleNoToCode = new java.util.HashMap<>();
        for (String code : roleCodes) roleNoToCode.put(code, code);
        DataScopeContext.executeWithoutScope(() -> roles.selectList(new LambdaQueryWrapper<IamRole>().in(IamRole::getCode, roleCodes)))
                .forEach(r -> roleNoToCode.putIfAbsent(r.getRoleNo(), r.getCode()));
        Map<String, String> empRole = new LinkedHashMap<>();
        DataScopeContext.executeWithoutScope(() -> employeeRoles.selectList(new LambdaQueryWrapper<IamEmployeeRole>()
                .in(IamEmployeeRole::getRoleNo, roleNoToCode.keySet())))
                .forEach(r -> empRole.putIfAbsent(r.getEmployeeNo(), roleNoToCode.get(r.getRoleNo())));
        DataScopeContext.executeWithoutScope(() -> employees.selectList(new LambdaQueryWrapper<IamEmployee>()
                .in(IamEmployee::getRoleNo, roleNoToCode.keySet())))
                .forEach(e -> empRole.putIfAbsent(e.getEmployeeNo(), roleNoToCode.get(e.getRoleNo())));
        if (empRole.isEmpty()) return List.of();
        return DataScopeContext.executeWithoutScope(() -> employees.selectList(new LambdaQueryWrapper<IamEmployee>()
                        .in(IamEmployee::getEmployeeNo, empRole.keySet()).eq(IamEmployee::getStatus, EmployeeStatus.ACTIVE.name())
                        .orderByAsc(IamEmployee::getId).last("limit " + Math.max(1, Math.min(limit, 500)))))
                .stream().map(e -> new EmployeeBrief(e.getEmployeeNo(), e.getName(), e.getStatus(), empRole.get(e.getEmployeeNo())))
                .toList();
    }

    @Override
    public List<EmployeeBrief> activeInRegion(Set<String> roleCodes, String regionId, int limit) {
        return activeByRoles(roleCodes, 500).stream().filter(e -> covers(e, regionId)).limit(Math.max(1, limit)).toList();
    }

    private boolean covers(EmployeeBrief e, String regionId) {
        var own = DataScopeContext.executeWithoutScope(() -> scopes.get("EMPLOYEE", e.employeeNo()));
        String type, refs;
        if (own != null) {
            type = own.scopeType();
            refs = own.scopeRefs();
        } else {
            var byRole = e.roleCode() == null ? null : DataScopeContext.executeWithoutScope(() -> scopes.get("ROLE", e.roleCode()));
            if (byRole != null) {
                type = byRole.scopeType();
                refs = byRole.scopeRefs();
            } else {
                IamRole r = e.roleCode() == null ? null : DataScopeContext.executeWithoutScope(() -> roles.selectOne(
                        new LambdaQueryWrapper<IamRole>().eq(IamRole::getCode, e.roleCode()).last("limit 1")));
                type = r == null ? null : r.getDataScope();
                refs = r == null ? null : r.getScopeRefs();
            }
        }
        if ("ALL".equals(type)) return true;
        if (!"REGION".equals(type) || regionId == null || refs == null) return false;
        // 数据范围服务读回的是逗号串，角色表上存的是 JSON 数组 —— 去掉括号引号后按逗号比
        return java.util.Arrays.stream(refs.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).anyMatch(regionId::equals);
    }
}
