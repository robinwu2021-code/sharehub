package ai.neargo.sharehub.platform.org.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 员工绩效（iam_staff_perf）—— 按账期的人效快照，UK(employee_no, period)，[db-design §2.2]。
 *
 * <p>{@code period} 是账期 {@code YYYY-MM}（[db-design §1.5] 时间列约定：账期用 CHAR(7)）。
 * {@code employeeName} 是**写入时的姓名快照**：历史账期不该随员工改名而回溯。
 * {@code score} 是百分数口径 0..100（[db-design §1.5]「*_percent / 统计派生 = 百分数」）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_staff_perf")
public class IamStaffPerf extends BaseEntity {
    private String employeeNo;
    private String employeeName;
    /** 账期 YYYY-MM。 */
    private String period;
    /** 统计口径角色码 OPS/CS/FINANCE/BD/…（非 iam_role.role_no）。 */
    private String role;
    private Integer handled;
    private Integer avgResolveMins;
    /** 绩效评分 0..100。 */
    private BigDecimal score;
}
