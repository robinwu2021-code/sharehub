package ai.neargo.sharehub.platform.iam;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.audit.AuditChanges;
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
    private final MenuService menuService;

    public IamAdminController(RoleMapper roleMapper, RolePermMapper rolePermMapper, PermissionMapper permissionMapper,
                              MenuMapper menuMapper, PermissionService permissionService, PermVersion permVersion,
                              MenuService menuService) {
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.permissionMapper = permissionMapper;
        this.menuMapper = menuMapper;
        this.permissionService = permissionService;
        this.permVersion = permVersion;
        this.menuService = menuService;
    }

    /** 权限码目录（构建分配选择器）。 */
    @GetMapping("/permissions")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<IamPermission> permissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<IamPermission>()
                .orderByAsc(IamPermission::getModule));
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

    /**
     * **完整**菜单树（不按权限剪枝）。两个用途，都要求看得见全部：
     * <ul>
     *   <li>菜单管理界面 —— 要能看到、编辑那些当前管理员自己也看不到的菜单；</li>
     *   <li>角色的「可见菜单预览」—— 拿某个角色的权限码去算他会看到什么，
     *       起点必须是全量树，用当前会话那棵（已剪枝）算出来的是错的。</li>
     * </ul>
     *
     * <p>与 {@code GET /api/auth/menus} 的分工：那个是**我**看得到的（已剪枝，前端直接渲染），
     * 这个是**全部**（管理用）。此前本方法直接返回实体 {@code IamMenu} ——
     * 与仓库「不拿实体当出参」的方向相反，且少了 children 结构，改成同一个 MenuNode。
     */
    /** 菜单可改的字段。**不含 path / parentNo / type** —— 理由见 {@link #updateMenu}。 */
    public record MenuPatch(String name, String nameEn, String nameAr, String groupName,
                            Integer sort, Integer visible, String perm) {
    }

    /**
     * 改一个菜单项。**只改「怎么显示、谁看得到」，不改「指向哪」。**
     *
     * <h2>为什么不让改 path / 新增 / 物理删除</h2>
     * 菜单项的 {@code path} 必须指向一个**真实存在的前端路由** ——
     * 在界面上填一个 {@code /foo} 得到的是一个点进去白屏的入口，而不报错。
     * 新增菜单同理：页面本来就要发版才有。所以这里只给运营真正用得上的那几样：
     * 改名（中/英/阿）· 分组标题 · 排序 · 显隐 · 挂哪个权限码。
     *
     * <p><b>没有物理删除</b>，只有 {@code visible=0}：删了就没了，停用可逆
     * （与仓库「契约禁止 delete*」同一条纪律）。
     *
     * <h2>两道护栏</h2>
     * <ol>
     *   <li><b>perm 必须在目录里</b>。挂一个没人强制的码，等于这个菜单对谁都不可见
     *       （除超管），而**不报错** —— 配的人以为配好了，用的人以为功能没做。</li>
     *   <li><b>不能把自己锁在外面</b>。菜单改坏了，运营连「进来改回去」的入口都没有。
     *       所以改完立刻用超管视角重算一遍：菜单管理自己那一支要是没了，直接回滚。</li>
     * </ol>
     */
    @PutMapping("/menus/{menuNo}")
    @PreAuthorize("@perm.can('org:role:update')")
    @Transactional
    public MenuService.MenuNode updateMenu(@PathVariable String menuNo, @RequestBody MenuPatch body) {
        IamMenu cur = menuMapper.selectOne(new LambdaQueryWrapper<IamMenu>()
                .eq(IamMenu::getMenuNo, menuNo));
        if (cur == null) {
            throw new IllegalArgumentException("菜单不存在: " + menuNo);
        }
        if (body.perm() != null && !body.perm().isBlank()
                && permissionMapper.selectCount(new LambdaQueryWrapper<IamPermission>()
                        .eq(IamPermission::getCode, body.perm())) == 0) {
            throw new IllegalArgumentException(
                    "权限码不在目录里：" + body.perm() + "。挂上去这个菜单对谁都不可见（除超管），"
                            + "而且不会报错——先把它登记进 iam_permission。");
        }

        // 改前改后都记：菜单可见性出了问题时，「谁在什么时候把它藏了」是第一个要答的
        AuditChanges.record("菜单名", cur.getName(), body.name());
        AuditChanges.record("权限码", cur.getPerm(), body.perm());
        AuditChanges.record("是否可见", cur.getVisible(), body.visible());
        AuditChanges.record("排序", cur.getSort(), body.sort());

        if (body.name() != null && !body.name().isBlank()) cur.setName(body.name());
        if (body.nameEn() != null) cur.setNameEn(body.nameEn());
        if (body.nameAr() != null) cur.setNameAr(body.nameAr());
        if (body.groupName() != null) cur.setGroupName(body.groupName());
        if (body.sort() != null) cur.setSort(body.sort());
        if (body.visible() != null) cur.setVisible(body.visible());
        if (body.perm() != null) cur.setPerm(body.perm().isBlank() ? null : body.perm());
        menuMapper.updateById(cur);

        assertAdminCanStillGetBackIn();
        return menuService.tree().stream()
                .flatMap(n -> java.util.stream.Stream.concat(java.util.stream.Stream.of(n), n.children().stream()))
                .filter(n -> n.menuNo().equals(menuNo)).findFirst().orElseThrow();
    }

    /**
     * 改完之后，超管还进得来吗？
     *
     * <p>菜单是运营端**唯一**的入口。把「员工与权限」那一支藏了之后，
     * 谁都没法再进来把它改回去 —— 只能改库。所以这道闸拦在事务里，不过就回滚。
     *
     * <p>用超管视角（{@code *}）而不是当前操作者：当前操作者可能本来就看不到某些菜单，
     * 拿他判会把「他看不到」误判成「被锁死了」。
     */
    private void assertAdminCanStillGetBackIn() {
        LoginUser su = new LoginUser(Realm.STAFF, "__guard__", "__guard__", "ADMIN",
                List.of("*"), "MAIN", "", DataScopeSpec.ALL);
        boolean reachable = menuService.visibleFor(su).stream()
                .anyMatch(n -> ADMIN_SECTION.equals(n.menuNo()));
        if (!reachable) {
            throw new IllegalStateException(
                    "这一改之后「" + ADMIN_SECTION + "」对超管也不可见了——菜单是运营端唯一的入口，"
                            + "改成这样之后谁都进不来把它改回去。已回滚。");
        }
    }

    /** 回得来的那扇门：菜单管理自己就在这一支下面。 */
    private static final String ADMIN_SECTION = "M_org";

    @GetMapping("/menus")
    @PreAuthorize("@perm.can('org:role:read')")
    public List<MenuService.MenuNode> menus() {
        return menuService.tree();
    }
}
