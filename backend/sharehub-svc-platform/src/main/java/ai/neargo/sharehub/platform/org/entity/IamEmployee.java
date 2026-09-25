package ai.neargo.sharehub.platform.org.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 员工（iam_employee）—— 组织域聚合根，[db-design §2.2]。
 *
 * <p>{@code phone} 落**掩码值**：明文归 {@code pb_pii}（[db-design §4]），
 * 出参再掩一次由 service 兜底（[api/README §1.6]「服务端出参即脱敏」）。
 * {@code roleNo} 是**主角色**（列表展示用）；多角色在 {@link IamEmployeeRole}，两者由 service 同步。
 * 离职不删行，置 {@code status=LEFT}（全站零 DELETE）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iam_employee")
public class IamEmployee extends BaseEntity {
    private String employeeNo;
    private String name;
    /** 掩码手机号（明文在 pb_pii，经脱敏接口访问）。 */
    private String phone;
    private String email;
    private String deptNo;
    /** 主角色（iam_role.role_no）。 */
    private String roleNo;
    /** pb_auth 凭据引用（realm=STAFF）。 */
    private String userId;

    /**
     * 邮箱的 HMAC 哈希（V114）。登录按它等值查 —— 明文 email 列不参与查找。
     *
     * <p>可空：存量员工里有大量没有邮箱的行（测试库 200 个在职里 195 个邮箱手机皆空）。
     * 由 {@code staff-identity-backfill} 任务回填，SQL 算不出来（需要运行期的 pepper）。
     */
    private String emailHash;

    /** 手机号的 HMAC 哈希（V114）。同 {@link #emailHash}。 */
    private String phoneHash;

    /** pepper 版本。轮换后据它判断哪些行还没重算。 */
    private Integer hashVer;
    /** ACTIVE / LEFT */
    private String status;
}
