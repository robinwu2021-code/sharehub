package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RolePermMapper;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.RoleRowVO;
import ai.neargo.sharehub.platform.org.entity.IamEmployeeRole;
import ai.neargo.sharehub.platform.org.mapper.IamEmployeeRoleMapper;
import ai.neargo.sharehub.platform.org.service.RoleQueryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 角色列表/归档实现。角色表是小表，计数在内存折叠，不写 GROUP BY 子查询。 */
@Service
public class RoleQueryServiceImpl implements RoleQueryService {

    private final RoleMapper roles;
    private final RolePermMapper rolePerms;
    private final IamEmployeeRoleMapper employeeRoles;

    public RoleQueryServiceImpl(RoleMapper roles, RolePermMapper rolePerms,
                                IamEmployeeRoleMapper employeeRoles) {
        this.roles = roles;
        this.rolePerms = rolePerms;
        this.employeeRoles = employeeRoles;
    }

    @Override
    public List<RoleRowVO> list(boolean showArchived) {
        Map<String, Long> permCounts = rolePerms.selectList(null).stream()
                .collect(Collectors.groupingBy(IamRolePerm::getRoleNo, Collectors.counting()));
        Map<String, Long> memberCounts = employeeRoles.selectList(null).stream()
                .collect(Collectors.groupingBy(IamEmployeeRole::getRoleNo, Collectors.counting()));
        return roles.selectList(new LambdaQueryWrapper<IamRole>().orderByAsc(IamRole::getId)).stream()
                .filter(r -> showArchived || r.getArchivedAt() == null)
                .map(r -> toVO(r, permCounts.getOrDefault(r.getRoleNo(), 0L),
                        memberCounts.getOrDefault(r.getRoleNo(), 0L)))
                .toList();
    }

    @Override
    public RoleRowVO archive(String roleNo) {
        IamRole r = require(roleNo);
        if (r.getBuiltin() != null && r.getBuiltin() == 1) {
            throw new IllegalArgumentException("内置角色不可归档: " + roleNo);
        }
        r.setArchivedAt(LocalDateTime.now(ZoneOffset.UTC));
        roles.updateById(r);
        return toVO(r, 0L, 0L);
    }

    @Override
    public RoleRowVO unarchive(String roleNo) {
        IamRole r = require(roleNo);
        // 显式置空：MP 默认 NOT_NULL 更新策略下 updateById 会忽略 null 字段，archived_at 清不掉
        roles.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<IamRole>()
                .eq("role_no", roleNo).set("archived_at", null));
        r.setArchivedAt(null);
        return toVO(r, 0L, 0L);
    }

    private IamRole require(String roleNo) {
        IamRole r = roles.selectOne(new LambdaQueryWrapper<IamRole>()
                .eq(IamRole::getRoleNo, roleNo).last("limit 1"));
        if (r == null) throw new IllegalArgumentException("角色不存在: " + roleNo);
        return r;
    }

    private static RoleRowVO toVO(IamRole r, Long permCount, Long memberCount) {
        return new RoleRowVO(r.getRoleNo(), r.getCode(), r.getName(), permCount, memberCount,
                r.getBuiltin() != null && r.getBuiltin() == 1, r.getDataScope(), r.getScopeRefs(),
                r.getArchivedAt() == null ? null : r.getArchivedAt().toString());
    }
}
