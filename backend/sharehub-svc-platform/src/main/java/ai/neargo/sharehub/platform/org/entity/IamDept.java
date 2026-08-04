package ai.neargo.sharehub.platform.org.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 组织架构（iam_dept）—— 自引用树，[db-design §2.2]。
 *
 * <p><b>两个刻意的设计</b>：
 * <ul>
 *   <li>{@code parentNo} 只表达「上一级」，查子树要递归；因此额外冗余 {@code path}
 *       （祖先路径 {@code /D1/D3/}），一条 {@code LIKE '/D1/%'} 就能取整棵子树，
 *       避免递归 CTE 或 N+1。{@code path} 由 service 在保存时重算，调用方不必填。</li>
 *   <li><b>{@code memberCount} 不落列</b>（[db-design §1.4]「计数列不是列」）——
 *       它会与 {@code iam_employee} 的真实分布漂移。出参里的成员数由聚合实时算。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_dept")
public class IamDept extends BaseEntity {
    private String deptNo;
    /** 上级部门（自引用，逻辑外键）；顶级为 null。 */
    private String parentNo;
    private String name;
    private String nameEn;
    private String nameAr;
    /** 负责人 employee_no。 */
    private String leaderNo;
    /** 负责人姓名快照（列表直出，不为渲染再查一次员工表）。 */
    private String leaderName;
    /** 祖先路径 {@code /D1/D3/}，由 service 维护，便于子树查询。 */
    private String path;
    private Integer sort;
    /** ACTIVE / DISABLED */
    private String status;
}
