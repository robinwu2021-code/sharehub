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
    public RoleRowVO save(String roleNo, RoleRowVO in) {
        if (in == null || in.name() == null || in.name().isBlank()) {
            throw new IllegalArgumentException("角色名称必填");
        }
        boolean create = roleNo == null || roleNo.isBlank();
        IamRole r;
        if (create) {
            if (in.code() == null || in.code().isBlank()) {
                throw new IllegalArgumentException("角色编码必填");
            }
            if (roles.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IamRole>()
                    .eq(IamRole::getCode, in.code().trim())) > 0) {
                // 编码是权限判定的连接键，重了就有两套权限抢同一个 code
                throw new IllegalArgumentException("角色编码已存在: " + in.code());
            }
            r = new IamRole();
            r.setRoleNo(ai.neargo.common.core.IdGenerator.next(ai.neargo.sharehub.common.BizKey.ROLE));
            r.setTenantId("MAIN");
            r.setCode(in.code().trim());
            r.setBuiltin(0);   // 内置角色只能由种子产生，接口一律建普通角色
        } else {
            r = require(roleNo);
            if (r.getBuiltin() != null && r.getBuiltin() == 1) {
                throw new IllegalArgumentException("内置角色不可修改: " + roleNo);
            }
            // code 不受理：RolePerms 按 code 认角色，改掉等于把一整套权限判定
            // 悄悄指向一个不存在的角色 —— 页面不报错，只是那个角色的人忽然什么都看不见。
        }
        r.setName(in.name().trim());
        // dataScope / scopeRefs 不在这里写：它们有专门的写入口（saveRoleDataScope）。
        if (create) roles.insert(r); else roles.updateById(r);
        return toVO(require(r.getRoleNo()), 0L, 0L);
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
