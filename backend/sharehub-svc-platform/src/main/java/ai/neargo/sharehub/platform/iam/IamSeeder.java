package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamMenu;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamPermission;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.DataScopeMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.MenuMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.PermissionMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RolePermMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * iam_* 种子（幂等，首 boot 灌）。**从硬编码 {@code RolePerms} + 12 导航模块等价导出**，
 * 保证切库后权限矩阵/菜单与硬编码期一致（迁移零行为差异）。
 */
@Component
public class IamSeeder implements ApplicationRunner {

    private static final Map<String, String> ROLE_NAME = Map.of(
            "ADMIN", "运营管理员", "OPS", "运维", "CS", "客服", "FINANCE", "财务",
            "BD", "拓展", "VIEWER", "只读", "AGENT", "代理商");

    // 12 模块菜单：{module, name, path, icon, 代表查看码}
    private static final String[][] MENUS = {
            {"dashboard", "经营看板", "/", "LayoutDashboard", "dashboard:overview:read"},
            {"device", "设备管理", "/devices", "Server", "device:cabinet:read"},
            {"location", "站点与点位", "/locations", "MapPin", "location:poi:read"},
            {"order", "订单管理", "/orders", "ReceiptText", "order:order:read"},
            {"pricing", "计费定价", "/pricing", "Tag", "pricing:plan:read"},
            {"finance", "财务管理", "/finance", "Wallet", "finance:share_rule:read"},
            {"workorder", "工单管理", "/work-orders", "Wrench", "workorder:wo:read"},
            {"user", "用户管理", "/users", "UserCircle", "user:cuser:read"},
            {"marketing", "营销管理", "/marketing", "Ticket", "marketing:coupon:read"},
            {"agent", "代理商管理", "/agents", "Handshake", "agent:agent:read"},
            {"org", "员工与权限", "/employees", "Users", "org:employee:read"},
            {"system", "系统设置", "/system/vendors", "Settings", "system:dict:read"},
    };

    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final PermissionMapper permissionMapper;
    private final DataScopeMapper dataScopeMapper;
    private final MenuMapper menuMapper;

    public IamSeeder(RoleMapper roleMapper, RolePermMapper rolePermMapper, PermissionMapper permissionMapper,
                     DataScopeMapper dataScopeMapper, MenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.permissionMapper = permissionMapper;
        this.dataScopeMapper = dataScopeMapper;
        this.menuMapper = menuMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (roleMapper.selectCount(null) == 0) {
            RolePerms.MAP.forEach((code, perms) -> {
                IamRole r = new IamRole();
                r.setRoleNo(code);            // MVP：role_no = code
                r.setTenantId("MAIN");
                r.setCode(code);
                r.setName(ROLE_NAME.getOrDefault(code, code));
                r.setBuiltin(1);              // 内置只读
                r.setDataScope("AGENT".equals(code) ? "AGENT" : "ALL");
                roleMapper.insert(r);
                perms.forEach(pc -> {
                    IamRolePerm rp = new IamRolePerm();
                    rp.setRoleNo(code);
                    rp.setPermCode(pc);
                    rolePermMapper.insert(rp);
                });
                IamDataScope ds = new IamDataScope();
                ds.setSubjectType("ROLE");
                ds.setSubjectNo(code);
                ds.setScopeType("AGENT".equals(code) ? "AGENT" : "ALL");
                dataScopeMapper.insert(ds);
            });
            // 演示自定义（非内置）角色，供后台改权限 + 口径 B 在线生效验证
            IamRole custom = new IamRole();
            custom.setRoleNo("CUSTOM");
            custom.setTenantId("MAIN");
            custom.setCode("CUSTOM");
            custom.setName("自定义演示角色");
            custom.setBuiltin(0);            // 可改
            custom.setDataScope("ALL");
            roleMapper.insert(custom);
            for (String pc : List.of("dashboard:overview:read", "device:cabinet:read")) {
                IamRolePerm rp = new IamRolePerm();
                rp.setRoleNo("CUSTOM");
                rp.setPermCode(pc);
                rolePermMapper.insert(rp);
            }
            IamDataScope cds = new IamDataScope();
            cds.setSubjectType("ROLE");
            cds.setSubjectNo("CUSTOM");
            cds.setScopeType("ALL");
            dataScopeMapper.insert(cds);
        }
        // 内置角色权限**增量对齐**（无论是否首灌都跑）：RolePerms 后加的码补进 iam_role_perm。
        // **只增不删** —— 删除可能吞掉管理员在线授予的码；减权走管理界面，不走种子。
        // 没有这一步，「SSOT 加了码但库是老的」会让新码永远到不了已初始化的环境。
        RolePerms.MAP.forEach((code, perms) -> {
            java.util.Set<String> existing = rolePermMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IamRolePerm>()
                                    .eq(IamRolePerm::getRoleNo, code)).stream()
                    .map(IamRolePerm::getPermCode).collect(java.util.stream.Collectors.toSet());
            perms.stream().filter(pc -> !existing.contains(pc)).forEach(pc -> {
                IamRolePerm rp = new IamRolePerm();
                rp.setRoleNo(code);
                rp.setPermCode(pc);
                rolePermMapper.insert(rp);
            });
        });
        if (permissionMapper.selectCount(null) == 0) {
            RolePerms.MAP.values().stream().flatMap(List::stream).distinct().forEach(code -> {
                IamPermission p = new IamPermission();
                p.setCode(code);
                p.setModule(code.contains(":") ? code.substring(0, code.indexOf(':')) : code);
                p.setName(code);
                permissionMapper.insert(p);
            });
        }
        if (menuMapper.selectCount(null) == 0) {
            int sort = 1;
            for (String[] m : MENUS) {
                IamMenu menu = new IamMenu();
                menu.setMenuNo("M_" + m[0]);
                menu.setName(m[1]);
                menu.setType("MENU");
                menu.setPath(m[2]);
                menu.setIcon(m[3]);
                menu.setPerm(m[4]);
                menu.setSort(sort++);
                menu.setVisible(1);
                menu.setStatus("ACTIVE");
                menuMapper.insert(menu);
            }
        }
    }
}
