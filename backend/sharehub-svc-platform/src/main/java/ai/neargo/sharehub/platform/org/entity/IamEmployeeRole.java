package ai.neargo.sharehub.platform.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工-角色映射（iam_employee_role）——「一个员工可兼多角色」的落法，UK(employee_no, role_no)。
 *
 * <p>纯映射表，DDL 无 {@code version}/{@code deleted} → **不继承 BaseEntity**；
 * 改绑定走「按员工删旧行 + 插新行」（物理删映射行不是业务软删，员工本体仍在）。
 */
@Data
@TableName("iam_employee_role")
public class IamEmployeeRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String employeeNo;
    private String roleNo;
    private LocalDateTime createdAt;
}
