package ai.neargo.sharehub.platform.org.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 数据范围挂在谁身上（{@code iam_data_scope.subject_type}）。
 *
 * <p><b>两级并存是有意的</b>：角色的范围管一类人，员工的范围管「这一个人比他的角色
 * 多看或少看」。只有角色一级的话，「某个员工要看得更窄」只能给他单开一个角色 ——
 * 而角色是给一类人用的，为一个人开一个会让角色表迅速失去意义。
 */
public enum DataScopeSubject {

    ROLE,
    EMPLOYEE;

    public static Optional<DataScopeSubject> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
