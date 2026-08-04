package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.auth.PermVersion;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamPermission;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.MenuMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.PermissionMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RolePermMapper;
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

    /**
     * 读某角色已分配的权限码（勾选树回显）。
     *
     * <p><b>为什么必须有</b>：写侧 {@code PUT} 是**覆盖写**（先 delete 再 insert）。
     * 没有读侧，前端勾选树只能空着打开 —— 管理员勾两个一保存，该角色其余权限全被抹掉。
     * 这是数据丢失级缺口，不是「少个便利接口」。
     *
     * <p>数据源是 {@code iam_role_perm}（与 {@code PermissionService.permsOfRole} / 鉴权同一张表），
     * **不是** {@code RolePerms.MAP} —— 那个静态 Map 现在只剩 {@code IamSeeder} 首启灌种子用，
     * 不参与鉴权也不反映运行时改动；从它读会与实际生效权限不一致。
     *
     * <p>故意**不走** {@code PermissionService.permsOfRole}：那里带进程内缓存，
     * 而缓存的作用是加速鉴权、可能滞后于刚落库的写（多实例下尤甚）；
     * 回显要的是「库里现在是什么」，所以直读表。
     *
     * <p>角色不存在 → 400（区别于「存在但没配权限」的 200 + 空数组，前端两种情况要分开处理）。
     */
    @GetMapping("/roles/{roleNo}/permissions")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<String> rolePermissions(@PathVariable String roleNo) {
        if (roleMapper.selectOne(new LambdaQueryWrapper<IamRole>().eq(IamRole::getRoleNo, roleNo)) == null) {
            throw new IllegalArgumentException("角色不存在: " + roleNo);
        }
        return rolePermMapper.selectList(new LambdaQueryWrapper<IamRolePerm>()
                        .eq(IamRolePerm::getRoleNo, roleNo)
                        .orderByAsc(IamRolePerm::getPermCode))   // 稳定序：勾选树 diff 与快照测试才可比
                .stream().map(IamRolePerm::getPermCode).distinct().toList();
    }

    /** 给角色分配权限码（覆盖写）→ 失效缓存 + bump 版本（在线员工下一请求即生效）。内置角色只读。 */
    @PutMapping("/roles/{roleNo}/permissions")
    // 权限码统一为 `:update`：全站 7 处写侧动词都是 update（device:powerbank:update / user:risk:update /
    // system:*:update …），`org:role:write` 是唯一孤例。RBAC SSOT（功能权限清单）此前只定义了
    // `org:role:read`，写侧未定义 —— 按惯例收敛到 update，SSOT 待补登记。
    @PreAuthorize("@perm.can('org:role:update')")
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
