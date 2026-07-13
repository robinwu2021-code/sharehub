package ai.neargo.powerbank.platform.iam;

import ai.neargo.powerbank.auth.PermVersion;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamPermission;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.MenuMapper;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.PermissionMapper;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.RolePermMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运营端权限后台配置（管理员运行时改，改完即失效缓存 + bump 版本 → 口径 B 在线生效）。
 * 详细步骤见 docs/technical/运营端权限-动态配置实现步骤.md。内置角色 {@code builtin=1} 只读。
 */
@RestController
@RequestMapping("/api/platform/iam")   // 独立前缀，避免与 portal/ops/PlatformController(/api/platform/roles 等) 冲突
public class IamAdminController {

    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final PermissionMapper permissionMapper;
    private final MenuMapper menuMapper;
    private final PermissionService permissionService;
    private final PermVersion permVersion;

    public IamAdminController(RoleMapper roleMapper, RolePermMapper rolePermMapper, PermissionMapper permissionMapper,
                              MenuMapper menuMapper, PermissionService permissionService, PermVersion permVersion) {
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.permissionMapper = permissionMapper;
        this.menuMapper = menuMapper;
        this.permissionService = permissionService;
        this.permVersion = permVersion;
    }

    /** 权限码目录（构建分配选择器）。 */
    @GetMapping("/permissions")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<IamPermission> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<IamPermission>()
                .orderByAsc(IamPermission::getModule));
    }

    /** 角色列表。 */
    @GetMapping("/roles")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<IamRole> roles() {
        return roleMapper.selectList(new LambdaQueryWrapper<IamRole>().orderByAsc(IamRole::getId));
    }

    /** 给角色分配权限码（覆盖写）→ 失效缓存 + bump 版本（在线员工下一请求即生效）。内置角色只读。 */
    @PutMapping("/roles/{roleNo}/permissions")
    @PreAuthorize("@perm.can('org:role:write')")
    @Transactional
    public Map<String, Object> setRolePermissions(@PathVariable String roleNo, @RequestBody Map<String, List<String>> body) {
        IamRole role = roleMapper.selectOne(new LambdaQueryWrapper<IamRole>().eq(IamRole::getRoleNo, roleNo));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在: " + roleNo);
        }
        if (Integer.valueOf(1).equals(role.getBuiltin())) {
            throw new IllegalArgumentException("内置角色只读，不可改权限: " + roleNo);
        }
        List<String> perms = body.getOrDefault("perms", List.of());
        rolePermMapper.delete(new LambdaQueryWrapper<IamRolePerm>().eq(IamRolePerm::getRoleNo, roleNo));
        for (String code : perms) {
            IamRolePerm rp = new IamRolePerm();
            rp.setRoleNo(roleNo);
            rp.setPermCode(code);
            rolePermMapper.insert(rp);
        }
        permissionService.evict(roleNo);
        long v = permVersion.bump();
        return Map.of("roleNo", roleNo, "perms", perms, "permVersion", v);
    }

    /** 全量菜单树（后台维护用）。 */
    @GetMapping("/menus")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<IamMenu> menus() {
        return menuMapper.selectList(new LambdaQueryWrapper<IamMenu>().orderByAsc(IamMenu::getSort));
    }
}
