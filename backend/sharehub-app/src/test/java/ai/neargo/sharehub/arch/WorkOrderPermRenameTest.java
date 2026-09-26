package ai.neargo.sharehub.arch;

import ai.neargo.sharehub.platform.iam.RolePerms;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单三个端点从旧码（{@code wo:process} / {@code wo:audit}）改判真源表的新码
 * （{@code wo:handle} / {@code wo:close}）—— **证明没有任何角色的可达性因此改变**。
 *
 * <h3>为什么需要这一条</h3>
 * `WoExtController#rework` 的注释里，上一个人明确写了「此处不顺手改（**会改变现有角色的可见范围**，须单独放行）」。
 * 那是个负责任的判断：改权限码本来就可能悄悄放大或收窄某个角色的访问面，而这种变化不报错、没人会发现。
 *
 * <p>本条把那个担心**变成可验证的**：逐角色比较「旧码可达性」与「新码可达性」，两者必须逐个相等。
 * 相等就说明改名是纯粹的语义归位；不相等就说明真的动了访问面，那就不能改。
 *
 * <p>当前之所以相等：旧码在 [功能权限清单 §7] 里**根本不存在**（2026-07-29 已统一为新码），
 * 因此没有任何角色显式持有它；而能过旧码的 OPS / ADMIN 靠的是 {@code workorder:*} 与 {@code *} 通配 ——
 * 通配对新旧两个码一视同仁。
 *
 * <p><b>这条会在将来真正有用</b>：等哪天有人给某个角色显式配上 {@code workorder:wo:handle}
 * （比如把接单权下放给代理），本条会立刻红 —— 那时「改名无副作用」这个前提就不再成立了。
 */
class WorkOrderPermRenameTest {

    /** 旧码 → 真源表里的新码（2026-07-29 命名对齐，见功能权限清单 §7 末注）。 */
    private static final Map<String, String> RENAMED = Map.of(
            "workorder:wo:process", "workorder:wo:handle",
            "workorder:wo:audit", "workorder:wo:close",
            "workorder:wo:update", "workorder:inspection:update");

    @Test
    @DisplayName("★ 逐角色：旧码与新码的可达性完全一致（否则改判就动了访问面）")
    void renaming_does_not_change_any_role_reach() {
        List<String> problems = new java.util.ArrayList<>();
        for (String role : RolePerms.MAP.keySet()) {
            List<String> perms = RolePerms.of(role);
            RENAMED.forEach((oldCode, newCode) -> {
                boolean before = can(perms, oldCode);
                boolean after = can(perms, newCode);
                if (before != after) {
                    problems.add(role + ": " + oldCode + "=" + before + " 而 " + newCode + "=" + after
                            + " —— 改判会让这个角色的访问面发生变化，不能顺手改");
                }
            });
        }
        assertThat(problems).as("%s", String.join("\n", problems)).isEmpty();
    }

    @Test
    @DisplayName("反向确认：没有任何角色**显式**持有已废弃的旧码（持有了就说明真源表漏了一条）")
    void no_role_declares_the_retired_codes() {
        for (String role : RolePerms.MAP.keySet()) {
            assertThat(RolePerms.of(role))
                    .as("角色 %s 显式配了已废弃的旧码", role)
                    .doesNotContainAnyElementsOf(RENAMED.keySet());
        }
    }

    /** 与 {@code PermissionService} 同一套通配语义：{@code *} 或 {@code a:b:*} 前缀匹配。 */
    private static boolean can(List<String> perms, String code) {
        return perms.stream().anyMatch(p ->
                p.equals(code) || (p.endsWith("*") && code.startsWith(p.substring(0, p.length() - 1))));
    }
}
