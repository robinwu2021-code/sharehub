package ai.neargo.sharehub.platform.iam;

import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRole;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.DataScopeMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RoleMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RolePermMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import ai.neargo.sharehub.platform.org.service.DataScopeSubject;
import ai.neargo.sharehub.platform.org.service.DataScopeType;
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
    /*
     * 菜单种子已删（2026-09-24）。真源是 iam_menu，由 V67__menu_from_nav.sql 灌 127 行
     * （backend/scripts/gen-menu-seed.py 从 ops-web/lib/nav.ts 生成）。
     *
     * 这里原有一份 12 行的 MENUS 数组，守卫是 `menuMapper.selectCount(null) == 0`。
     * V67 之后它**永远不会执行**，但留着就是菜单的第二份定义，而且是旧的那份 ——
     * 与本文件上面记过的 CUSTOM 那个坑同一类：守卫的粒度碰巧等价，
     * 多一个来源就不再等价，且不会报错。所以删，而不是留着「反正跑不到」。
     */

    private final RoleMapper roleMapper;
    private final RolePermMapper rolePermMapper;
    private final DataScopeMapper dataScopeMapper;

    public IamSeeder(RoleMapper roleMapper, RolePermMapper rolePermMapper,
                     DataScopeMapper dataScopeMapper) {
        this.roleMapper = roleMapper;
        this.rolePermMapper = rolePermMapper;
        this.dataScopeMapper = dataScopeMapper;
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
                r.setDataScope(scopeOfRole(code));
                roleMapper.insert(r);
                perms.forEach(pc -> {
                    IamRolePerm rp = new IamRolePerm();
                    rp.setRoleNo(code);
                    rp.setPermCode(pc);
                    rolePermMapper.insert(rp);
                });
                IamDataScope ds = new IamDataScope();
                ds.setSubjectType(DataScopeSubject.ROLE.name());
                ds.setSubjectNo(code);
                ds.setScopeType(scopeOfRole(code));
                dataScopeMapper.insert(ds);
            });
        }

        // 演示自定义（非内置）角色，供后台改权限 + 口径 B 在线生效验证。
        //
        // **独立守卫，不能跟着内置角色那个 if 走**：内置角色由 V1__loc_agt_iam.sql
        // 迁移直接 INSERT，所以任何新库启动时 roleMapper.selectCount(null) 都 != 0，
        // 上面整块被跳过 —— CUSTOM 于是永远建不出来。
        // 共用开发库里它早就存在（比那条迁移还早），所以这个缺陷一直看不见，
        // 直到 2026-09-23 测试换到独立空库，OrgReadSideGapTest 立刻 400。
        //
        // 教训是守卫的**粒度**：用「有没有角色」判断「CUSTOM 在不在」，
        // 在只有一个来源时碰巧等价，多一个来源就不再等价，而且不会报错。
        if (roleMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IamRole>()
                .eq(IamRole::getCode, "CUSTOM")) == 0) {
            IamRole custom = new IamRole();
            custom.setRoleNo("CUSTOM");
            custom.setTenantId("MAIN");
            custom.setCode("CUSTOM");
            custom.setName("自定义演示角色");
            custom.setBuiltin(0);            // 可改
            custom.setDataScope(DataScopeType.ALL.name());
            roleMapper.insert(custom);
            for (String pc : List.of("dashboard:overview:read", "device:cabinet:read")) {
                IamRolePerm rp = new IamRolePerm();
                rp.setRoleNo("CUSTOM");
                rp.setPermCode(pc);
                rolePermMapper.insert(rp);
            }
            IamDataScope cds = new IamDataScope();
            cds.setSubjectType(DataScopeSubject.ROLE.name());
            cds.setSubjectNo("CUSTOM");
            cds.setScopeType(DataScopeType.ALL.name());
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
        /*
         * 权限码目录的种子已删（2026-09-24）。真源是 iam_permission，
         * 由 V72__perm_catalog_full.sql 灌 176 条
         * （backend/scripts/gen-perm-catalog.py 扫 @perm.can 生成，中文名读同目录的 tsv）。
         *
         * 这里原来灌的是 `RolePerms.MAP.values()` —— 那是「**内置角色持有**的码」，
         * 不是「**后端强制**的码」。两者从来不是一回事（64 vs 158），
         * 于是目录长期只有 57 条，**118 个真实权限在角色勾选树上选不到**，
         * 而管理员只会以为「这个权限没做」。
         *
         * 而且它把 name 设成 code 本身 —— 勾选树上那一行长得像乱码，没人会回来补。
         * 卡口：PermCatalogCoverageTest。
         */
    }
    /** 代理角色只看自己 agent_no，其余内置角色看全部（功能权限清单 §二）。 */
    private static String scopeOfRole(String roleCode) {
        return (DataScopeType.AGENT.is(roleCode) ? DataScopeType.AGENT : DataScopeType.ALL).name();
    }

}
