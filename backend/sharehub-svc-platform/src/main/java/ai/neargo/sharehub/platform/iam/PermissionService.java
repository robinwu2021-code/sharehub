package ai.neargo.sharehub.platform.iam;

import ai.neargo.common.data.scope.DataScopeResolver;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.AuthSubject;
import ai.neargo.common.security.rbac.PermissionResolver;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.PrincipalRefresher;
import ai.neargo.sharehub.platform.org.service.DataScopeSubject;
import ai.neargo.sharehub.platform.org.service.DataScopeType;
import ai.neargo.sharehub.auth.Realm;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.DataScopeMapper;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.RolePermMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限加载（取代硬编码 {@code RolePerms}）：角色→权限码 从 {@code iam_role_perm} 读，按角色缓存；
 * 数据范围从 {@code iam_data_scope} 读（ROLE 级 + AGENT realm 覆盖）。运行时可配置，改后 evict + bump 版本。
 */
@Service
public class PermissionService implements PrincipalRefresher, PermissionResolver, DataScopeResolver {

    private final RolePermMapper rolePermMapper;
    private final DataScopeMapper dataScopeMapper;
    private final ConcurrentHashMap<String, List<String>> permCache = new ConcurrentHashMap<>(); // roleCode → perms

    public PermissionService(RolePermMapper rolePermMapper, DataScopeMapper dataScopeMapper) {
        this.rolePermMapper = rolePermMapper;
        this.dataScopeMapper = dataScopeMapper;
    }

    /** 单角色权限码（含通配），缓存。 */
    public List<String> permsOfRole(String roleCode) {
        return permCache.computeIfAbsent(roleCode, rc -> rolePermMapper
                .selectList(new LambdaQueryWrapper<IamRolePerm>().eq(IamRolePerm::getRoleNo, rc))
                .stream().map(IamRolePerm::getPermCode).toList());
    }

    /** 多角色并集。 */
    public List<String> permsOfRoles(List<String> roleCodes) {
        return roleCodes.stream().flatMap(rc -> permsOfRole(rc).stream()).distinct().toList();
    }

    public void evict(String roleCode) {
        permCache.remove(roleCode);
    }

    public void evictAll() {
        permCache.clear();
    }

    /**
     * 数据范围。优先级 **AGENT realm → 员工级 → 角色级并集**。
     *
     * <p><b>员工级覆盖而不是取交/并</b>：它存在的理由就是「这个人要比他的角色看得更窄或更宽」
     * （见 {@code docs/technical/运营端-后端已就绪但未接的能力.md} A3）。
     * 取交集的话「更宽」永远实现不了，取并集的话「更窄」永远实现不了 ——
     * 两种都会让管理员配了却不生效，而界面上看不出任何异常。
     *
     * <p><b>员工级按 {@code userNo} 认人</b>（{@code LoginUser.userNo} = 运营端 username/employee_no）。
     * ⚠️ 在真实员工登录落地之前（v4/06 A3 凭据库），生产的会话 userNo 是 {@code admin}，
     * 匹配不到任何 {@code employee_no} —— 员工级范围**存得下、匹配不上**，
     * 走的仍是角色级那条路。这不是本方法的缺陷，但它会让「配了没反应」看起来像 bug，
     * 所以写在这里而不是留给人去猜。
     */
    public DataScopeSpec scopeOf(List<String> roleCodes, Realm realm, String agentNo, String userNo) {
        if (realm == Realm.AGENT && agentNo != null && !agentNo.isBlank()) {
            return DataScopeSpec.of(DataScopeType.AGENT.name(), Set.of(agentNo));
        }
        if (userNo != null && !userNo.isBlank()) {
            IamDataScope own = dataScopeMapper.selectOne(new LambdaQueryWrapper<IamDataScope>()
                    .eq(IamDataScope::getSubjectType, DataScopeSubject.EMPLOYEE.name())
                    .eq(IamDataScope::getSubjectNo, userNo)
                    .last("limit 1"));
            if (own != null) return specOf(List.of(own), userNo);
        }
        List<IamDataScope> rows = dataScopeMapper.selectList(new LambdaQueryWrapper<IamDataScope>()
                .eq(IamDataScope::getSubjectType, DataScopeSubject.ROLE.name())
                .in(IamDataScope::getSubjectNo, roleCodes));
        return specOf(rows, userNo);
    }

    /**
     * 若干条 {@code iam_data_scope} 行 → 一个 spec。任一 ALL → ALL；没有行 → ALL。
     *
     * <p><b>SELF 要带上本人的号</b>。此前 SELF 落库时 refs 被清空，而这里又把
     * refs 为空的规则滤掉 —— 于是「仅自己经手」**解析出来等于全部数据**，
     * 方向恰好反了，而且没有任何地方会报错。
     * 带上 userNo 之后，没登记 SELF 锚点的表由 {@code DataScopeHandler} 的
     * fail-closed 拼成 {@code 1=0}（看不到），错也错在安全的那一侧。
     */
    private DataScopeSpec specOf(List<IamDataScope> rows, String userNo) {
        if (rows.isEmpty() || rows.stream().anyMatch(r -> DataScopeType.ALL.is(r.getScopeType()))) {
            return DataScopeSpec.ALL;
        }
        List<DataScopeSpec.Rule> rules = rows.stream()
                .map(r -> new DataScopeSpec.Rule(r.getScopeType(), refsOf(r, userNo)))
                .filter(r -> !r.refs().isEmpty()).toList();
        return rules.isEmpty() ? DataScopeSpec.ALL : new DataScopeSpec(false, rules);
    }

    private static Set<String> refsOf(IamDataScope r, String userNo) {
        if (DataScopeType.SELF.is(r.getScopeType())) {
            return userNo == null || userNo.isBlank() ? Set.of() : Set.of(userNo);
        }
        return parseRefs(r.getScopeRefs());
    }

    // ===== SPI 实现（infra 契约 auth.PermissionResolver / DataScopeResolver）=====

    /** {@link PermissionResolver}：主体 → 权限码集合（读 iam_role_perm）。 */
    @Override
    public Set<String> resolvePermissions(AuthSubject subject) {
        return new HashSet<>(permsOfRoles(subject.roles()));
    }

    /** {@link DataScopeResolver}：主体 → 数据范围（iam_data_scope + AGENT 硬过滤，agentNo 从 attributes 取）。 */
    @Override
    public DataScopeSpec resolveDataScope(AuthSubject subject) {
        return scopeOf(subject.roles(), Realm.valueOf(subject.realm()), subject.attr("agentNo"),
                subject.subjectId());
    }

    /** 口径 B：按角色重建会话主体（权限/范围变更后刷新）。实现 {@link PrincipalRefresher}，走 SPI 保持一致。 */
    @Override
    public LoginUser rebuild(LoginUser old, List<String> roleCodes) {
        AuthSubject subject = new AuthSubject(old.realm().name(), old.userNo(), roleCodes, old.tenantId(),
                java.util.Map.of("agentNo", old.agentNo() == null ? "" : old.agentNo()));
        return new LoginUser(old.realm(), old.userNo(), old.username(), old.role(),
                List.copyOf(resolvePermissions(subject)), old.tenantId(), old.agentNo(),
                resolveDataScope(subject));
    }

    static Set<String> parseRefs(String s) {
        if (s == null || s.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(s.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
    }
}
