package ai.neargo.sharehub.platform.iam;

import java.util.Arrays;
import java.util.Optional;

/**
 * 员工在职与否（{@code iam_employee.status}，词表见 DDL 列注释 {@code ACTIVE/LEFT}）。
 *
 * <h3>为什么两个值也值得收</h3>
 * 这个词表管的是**授权**：{@code EmployeeRoles.rolesOf} 判「不是 ACTIVE 就一个角色都不给」。
 * 判据里打错一个字母不会报错 —— 它会让**所有人**都拿不到角色（写错成永不匹配），
 * 或者让离职的人照样拿到（写反了）。前者是全员失权、后者是静默越权，
 * 两种都不会抛异常。
 *
 * <p>另一处是 {@code EmployeeServiceImpl} 建档时的默认值，同一套词表。
 *
 * <h3>与运营端同名</h3>
 * 运营端 {@code lib/types/org.ts} 的 {@code EmployeeStatus} 同名同值，
 * 这一对因此进入两端同名词表比对（{@code StatusVocabularyAcrossEndsTest}）。
 */
public enum EmployeeStatus {

    /** 在职。 */
    ACTIVE,
    /** 离职 —— 失去全部角色，不是降级成某个默认角色。 */
    LEFT;

    public static Optional<EmployeeStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
