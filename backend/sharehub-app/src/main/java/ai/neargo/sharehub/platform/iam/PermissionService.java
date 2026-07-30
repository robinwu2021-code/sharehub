package ai.neargo.powerbank.platform.iam;

import ai.neargo.common.data.scope.DataScopeResolver;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.security.rbac.AuthSubject;
import ai.neargo.common.security.rbac.PermissionResolver;
import ai.neargo.powerbank.auth.LoginUser;
import ai.neargo.powerbank.auth.PrincipalRefresher;
import ai.neargo.powerbank.auth.Realm;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.powerbank.platform.iam.entity.IamEntities.IamRolePerm;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.DataScopeMapper;
import ai.neargo.powerbank.platform.iam.mapper.IamMappers.RolePermMapper;
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

    /** 数据范围：AGENT realm 覆盖式硬过滤；否则 iam_data_scope(ROLE) 并集（任一 ALL→ALL）。dim 用 String（通用）。 */
    public DataScopeSpec scopeOfRoles(List<String> roleCodes, Realm realm, String agentNo) {
        if (realm == Realm.AGENT && agentNo != null && !agentNo.isBlank()) {
            return DataScopeSpec.of("AGENT", Set.of(agentNo));
        }
        List<IamDataScope> rows = dataScopeMapper.selectList(new LambdaQueryWrapper<IamDataScope>()
                .eq(IamDataScope::getSubjectType, "ROLE").in(IamDataScope::getSubjectNo, roleCodes));
        if (rows.isEmpty() || rows.stream().anyMatch(r -> "ALL".equals(r.getScopeType()))) {
            return DataScopeSpec.ALL;
        }
        List<DataScopeSpec.Rule> rules = rows.stream()
                .map(r -> new DataScopeSpec.Rule(r.getScopeType(), parseRefs(r.getScopeRefs())))
                .filter(r -> !r.refs().isEmpty()).toList();
        return rules.isEmpty() ? DataScopeSpec.ALL : new DataScopeSpec(false, rules);
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
        return scopeOfRoles(subject.roles(), Realm.valueOf(subject.realm()), subject.attr("agentNo"));
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
