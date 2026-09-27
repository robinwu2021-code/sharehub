package ai.neargo.opskit.platform.core.app;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.actor.PrincipalRef;
import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.commons.security.RequiresPermission;
import ai.neargo.opskit.commons.security.SystemAction;
import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import ai.neargo.opskit.platform.api.Permission;
import ai.neargo.opskit.platform.api.directory.DirectorySource;
import ai.neargo.opskit.platform.api.directory.GroupKind;
import ai.neargo.opskit.platform.api.directory.GroupUpsert;
import ai.neargo.opskit.platform.api.directory.MemberUpsert;
import ai.neargo.opskit.platform.api.directory.MemberView;
import ai.neargo.opskit.platform.api.directory.RoleBinding;
import ai.neargo.opskit.platform.api.directory.RoleUpsert;
import ai.neargo.opskit.platform.api.directory.SyncBatch;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.dao.GroupDao;
import ai.neargo.opskit.platform.store.dao.GroupMemberDao;
import ai.neargo.opskit.platform.store.dao.MemberDao;
import ai.neargo.opskit.platform.store.dao.RoleBindingDao;
import ai.neargo.opskit.platform.store.dao.RoleDao;
import ai.neargo.opskit.platform.store.row.GroupRow;
import ai.neargo.opskit.platform.store.row.MemberRow;
import ai.neargo.opskit.platform.store.row.RoleRow;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionOperations;

/** 成员目录（21/07 O1-4 · O1-5）。 */
public class DirectoryAppService {

    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final int MAX_EXTERNAL_REF = 128;

    private final MemberDao members;
    private final GroupDao groups;
    private final GroupMemberDao groupMembers;
    private final RoleDao roles;
    private final RoleBindingDao bindings;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final TransactionOperations tx;
    private final AuditTrail audit;

    public DirectoryAppService(MemberDao members, GroupDao groups, GroupMemberDao groupMembers, RoleDao roles, RoleBindingDao bindings,
            BizNoGenerator ids, Clock clock, TransactionOperations tx, AuditTrail audit) {
        this.members = members;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.roles = roles;
        this.bindings = bindings;
        this.ids = ids;
        this.clock = clock;
        this.tx = tx;
        this.audit = audit;
    }

    /**
     * 即时开通：外部编号第一次出现就建成员；已存在则返回既有成员。并发开通由唯一键兜底，只会有一个成员。
     * 来源为 JIT 时只更新显示名（令牌里的属性 / 标签是单次请求的，不落库）；SYNC / CONSOLE 连属性与标签一起写。
     */
    @SystemAction("认证链路与同步内部调用")
    public MemberView provision(TenantContext t, MemberUpsert m, DirectorySource source) {
        requireExternalRef(m.externalRef());
        WorkspaceId ws = t.workspaceId();
        return tx.execute(s -> {
            long now = clock.millis();
            MemberRow row = members.findByExternalRef(ws, m.externalRef()).orElseGet(() -> {
                MemberRow fresh = new MemberRow(t.accountId().value(), ws.value(), ids.nextPublicId("mb"), m.externalRef(),
                        m.displayName(), source == DirectorySource.JIT ? Map.of() : m.attrs(),
                        source == DirectorySource.JIT ? Set.of() : m.scopes(), source.name(), "ACTIVE", now, now, now);
                members.insertIfAbsent(ws, fresh);
                return members.findByExternalRefForUpdate(ws, m.externalRef()).orElseThrow();
            });
            boolean nameChanged = m.displayName() != null && !m.displayName().equals(row.displayName());
            boolean profileChanged = source != DirectorySource.JIT && (!row.attrs().equals(m.attrs()) || !row.scopes().equals(new TreeSet<>(m.scopes())));
            if (nameChanged || profileChanged) {
                String name = m.displayName() != null ? m.displayName() : row.displayName();
                Map<String, Object> attrs = source == DirectorySource.JIT ? row.attrs() : m.attrs();
                Set<String> scopes = source == DirectorySource.JIT ? row.scopes() : new TreeSet<>(m.scopes());
                members.updateProfile(ws, row.memberId(), name, JsonColumns.write(attrs), JsonColumns.write(scopes), now);
                row = members.findById(ws, row.memberId()).orElseThrow();
            }
            return toView(row);
        });
    }

    /**
     * 令牌 / 应用给出的组 → 目录里该成员来源为 CLAIM 的组员关系。只写差异；目录里没有的组自动建成 TEAM。
     * SYNC 与 CONSOLE 来源的关系不受影响。
     */
    @SystemAction("认证链路内部调用")
    public void reconcileClaimedGroups(TenantContext t, String memberId, Set<String> claimed) {
        WorkspaceId ws = t.workspaceId();
        Set<String> desired = new TreeSet<>(claimed);
        desired.forEach(DirectoryAppService::requireCode);
        tx.executeWithoutResult(s -> {
            Set<String> current = groupMembers.groupsOfBySource(ws, memberId, DirectorySource.CLAIM.name());
            if (current.equals(desired)) {
                return;
            }
            long now = clock.millis();
            Set<String> toAdd = new TreeSet<>(desired);
            toAdd.removeAll(current);
            groups.insertMissing(ws, t.accountId().value(), toAdd, DirectorySource.CLAIM.name(), now);
            toAdd.forEach(g -> groupMembers.add(ws, g, memberId, DirectorySource.CLAIM.name(), now));
            current.stream().filter(g -> !desired.contains(g))
                    .forEach(g -> groupMembers.removeBySource(ws, g, memberId, DirectorySource.CLAIM.name()));
        });
    }

    @RequiresPermission("directory:manage")
    public void upsertGroup(Actor actor, GroupUpsert g) {
        Guards.require(actor, Permission.DIRECTORY_MANAGE);
        requireCode(g.code());
        TenantContext t = actor.tenant();
        tx.executeWithoutResult(s -> {
            groups.upsert(t.workspaceId(), groupRow(t, g, DirectorySource.CONSOLE));
            audit.record(actor, "group.upserted", "group:" + g.code(), Map.of("kind", g.kind().name()));
        });
    }

    /** 模板角色：{@code workspace} 传 {@link WorkspaceId#TEMPLATE}，账户下全部空间可见，空间可用同代号覆盖。 */
    @RequiresPermission("directory:manage")
    public void upsertRole(Actor actor, WorkspaceId workspace, RoleUpsert r) {
        Guards.require(actor, Permission.DIRECTORY_MANAGE);
        requireCode(r.code());
        List<FieldError> errors = new ArrayList<>();
        r.permissions().stream().filter(p -> Permission.ofCode(p).isEmpty()).sorted()
                .forEach(p -> errors.add(new FieldError("permissions", "opskit.platform.unknown_permission:" + p)));
        if (!errors.isEmpty()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "unknown permission codes", Map.of(), errors);
        }
        if (!workspace.isTemplate() && !workspace.equals(actor.tenant().workspaceId())) {
            throw new OpsKitException(CommonErrors.NOT_FOUND, "workspace not found");
        }
        tx.executeWithoutResult(s -> {
            roles.upsert(actor.tenant().accountId(), workspace,
                    new RoleRow(actor.tenant().accountId().value(), workspace.value(), r.code(), r.name(), new TreeSet<>(r.permissions()), clock.millis()));
            audit.record(actor.tenant().accountId(), workspace.value(), AuditTrail.who(actor), "role.upserted", "role:" + r.code(),
                    Map.of("permissions", new TreeSet<>(r.permissions())));
        });
    }

    @RequiresPermission("directory:manage")
    public void bindRole(Actor actor, RoleBinding b) {
        Guards.require(actor, Permission.DIRECTORY_MANAGE);
        PrincipalRef p = requireBindable(b.principal());
        tx.executeWithoutResult(s -> {
            bindings.bind(actor.tenant().workspaceId(), b.roleCode(), p.key(), DirectorySource.CONSOLE.name(), clock.millis());
            audit.record(actor, "role.bound", "role:" + b.roleCode(), Map.of("principal", p.key()));
        });
    }

    /** 目录同步（O1-5），语义见 {@link SyncBatch}。整批一个事务：要么全生效，要么全不生效。 */
    @RequiresPermission("directory:manage")
    public SyncResult sync(Actor actor, SyncBatch batch) {
        Guards.require(actor, Permission.DIRECTORY_MANAGE);
        TenantContext t = actor.tenant();
        WorkspaceId ws = t.workspaceId();
        batch.groups().forEach(g -> requireCode(g.code()));
        batch.memberships().keySet().forEach(DirectoryAppService::requireCode);
        batch.roleBindings().forEach(b -> requireBindable(b.principal()));
        String src = DirectorySource.SYNC.name();

        return tx.execute(s -> {
            long now = clock.millis();
            Map<String, String> memberIdByRef = new HashMap<>();
            for (MemberUpsert m : batch.members()) {
                memberIdByRef.put(m.externalRef(), provision(t, m, DirectorySource.SYNC).memberId());
            }
            for (GroupUpsert g : batch.groups()) {
                groups.upsert(ws, groupRow(t, g, DirectorySource.SYNC));
            }
            // 组员里引用的外部编号：本批没给档案的，按即时开通补建（不写属性）
            Set<String> referenced = new HashSet<>();
            batch.memberships().values().forEach(referenced::addAll);
            referenced.removeAll(memberIdByRef.keySet());
            for (String ref : referenced) {
                memberIdByRef.put(ref, provision(t, MemberUpsert.of(ref), DirectorySource.JIT).memberId());
            }
            for (Map.Entry<String, List<String>> e : batch.memberships().entrySet()) {
                groups.insertMissing(ws, t.accountId().value(), List.of(e.getKey()), src, now);
                List<String> ids = e.getValue().stream().map(memberIdByRef::get).toList();
                ids.forEach(id -> groupMembers.add(ws, e.getKey(), id, src, now));
                if (batch.mode() == SyncBatch.Mode.REPLACE) {
                    groupMembers.retainBySource(ws, e.getKey(), ids, src);
                }
            }
            Map<String, Set<String>> rolesByPrincipal = new LinkedHashMap<>();
            for (RoleBinding b : batch.roleBindings()) {
                String principal = resolvePrincipal(ws, b.principal(), memberIdByRef);
                rolesByPrincipal.computeIfAbsent(principal, k -> new TreeSet<>()).add(b.roleCode());
                bindings.bind(ws, b.roleCode(), principal, src, now);
            }
            if (batch.mode() == SyncBatch.Mode.REPLACE) {
                rolesByPrincipal.forEach((principal, keep) -> bindings.rolesOfPrincipalBySource(ws, principal, src).stream()
                        .filter(r -> !keep.contains(r)).forEach(r -> bindings.unbindBySource(ws, r, principal, src)));
            }
            SyncResult result = new SyncResult(memberIdByRef.size(), batch.groups().size(), batch.memberships().size(), batch.roleBindings().size());
            audit.record(actor, "directory.synced", "directory", Map.of("mode", batch.mode().name(), "members", result.members(),
                    "groups", result.groups(), "memberships", result.memberships(), "roleBindings", result.roleBindings()));
            return result;
        });
    }

    public record SyncResult(int members, int groups, int memberships, int roleBindings) {
    }

    /** 成员的有效组、角色与产品权限（目录部分；令牌声明由 ActorResolver 再并上）。 */
    @SystemAction("认证链路内部调用")
    public Effective effective(TenantContext t, String memberId, Set<String> extraGroups, Set<String> extraRoles) {
        WorkspaceId ws = t.workspaceId();
        Set<String> gs = new TreeSet<>(groupMembers.groupsOf(ws, memberId));
        gs.addAll(extraGroups);
        List<String> principals = new ArrayList<>();
        principals.add(PrincipalRef.member(memberId).key());
        gs.forEach(g -> principals.add(PrincipalRef.group(g).key()));
        Set<String> rs = new TreeSet<>(bindings.rolesOf(ws, principals));
        rs.addAll(extraRoles);
        return new Effective(gs, rs, permissionsOf(t.accountId(), ws, rs));
    }

    public record Effective(Set<String> groups, Set<String> roles, Set<String> permissions) {
    }

    @SystemAction("认证链路内部调用")
    public java.util.Optional<MemberView> findMember(TenantContext t, String memberId) {
        return members.findById(t.workspaceId(), memberId).map(DirectoryAppService::toView);
    }

    @SystemAction("认证链路内部调用：JIT 关闭时只认已存在的成员")
    public java.util.Optional<MemberView> findMemberByExternalRef(TenantContext t, String externalRef) {
        return members.findByExternalRef(t.workspaceId(), externalRef).map(DirectoryAppService::toView);
    }

    @SystemAction("会签快照 / 派单内部调用")
    public List<String> membersOf(TenantContext t, String groupCode) {
        return groupMembers.membersOf(t.workspaceId(), groupCode);
    }

    /** 同代号时空间角色覆盖模板角色。 */
    private Set<String> permissionsOf(AccountId account, WorkspaceId ws, Set<String> roleCodes) {
        Map<String, RoleRow> byCode = new HashMap<>();
        for (RoleRow r : roles.visibleIn(account, ws, roleCodes)) {
            byCode.merge(r.roleCode(), r, (a, b) -> a.workspaceId().isEmpty() ? b : a);
        }
        Set<String> perms = new TreeSet<>();
        byCode.values().forEach(r -> perms.addAll(r.permissions()));
        return perms;
    }

    private String resolvePrincipal(WorkspaceId ws, String principal, Map<String, String> memberIdByRef) {
        PrincipalRef p = PrincipalRef.parse(principal);
        // 同步里成员用外部编号（member:@E107）更方便：@ 前缀表示外部编号；本批没出现的，到目录里找
        if (p.kind() == PrincipalRef.Kind.MEMBER && p.id().startsWith("@")) {
            String ref = p.id().substring(1);
            String id = memberIdByRef.computeIfAbsent(ref, r -> members.findByExternalRef(ws, r).map(MemberRow::memberId).orElse(null));
            if (id == null) {
                throw new OpsKitException(CommonErrors.VALIDATION, "role binding references unknown member " + p.id(), Map.of(),
                        List.of(new FieldError("roleBindings", "opskit.platform.unknown_member")));
            }
            return PrincipalRef.member(id).key();
        }
        return p.key();
    }

    private static PrincipalRef requireBindable(String principal) {
        PrincipalRef p = PrincipalRef.parse(principal);
        if (p.kind() != PrincipalRef.Kind.MEMBER && p.kind() != PrincipalRef.Kind.GROUP) {
            throw new OpsKitException(CommonErrors.VALIDATION, "roles bind to members or groups only", Map.of(),
                    List.of(new FieldError("principal", "opskit.platform.unbindable_principal")));
        }
        return p;
    }

    private GroupRow groupRow(TenantContext t, GroupUpsert g, DirectorySource source) {
        Integer timeout = g.kind() == GroupKind.QUEUE ? g.claimTimeoutSec() : null;
        String orderBy = g.kind() == GroupKind.QUEUE ? Objects.requireNonNullElse(g.orderBy(), "PRIORITY") : null;
        Map<String, String> name = g.name().isEmpty() ? Map.of("en", g.code()) : g.name();
        return new GroupRow(t.accountId().value(), t.workspaceId().value(), g.code(), name, g.kind().name(), timeout, orderBy,
                null, source.name(), "ACTIVE", clock.millis());
    }

    private static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid code: " + code, Map.of(),
                    List.of(new FieldError("code", "opskit.validation.invalid_code")));
        }
    }

    private static void requireExternalRef(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > MAX_EXTERNAL_REF) {
            throw new OpsKitException(CommonErrors.VALIDATION, "invalid external ref", Map.of(),
                    List.of(new FieldError("externalRef", "opskit.validation.invalid")));
        }
    }

    private static MemberView toView(MemberRow r) {
        return new MemberView(r.workspaceId(), r.memberId(), r.externalRef(), r.displayName(), r.attrs(), r.scopes(),
                DirectorySource.valueOf(r.source()), r.status());
    }
}
