package ai.neargo.sharehub.platform.tenant.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户（tenant）—— 全局表，🔒 休眠口子。
 *
 * <p>{@code tenantNo} 是自然键：<b>业务表 {@code tenant_id} 存的就是它</b>（当前恒为 {@code MAIN}）。
 * {@code quota} 是 JSON 文本（设备数/订单量/坐席）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "tenant", excludeProperty = "tenantId")
public class Tenant extends BaseEntity {
    private String tenantNo;
    /** 运营主体名。 */
    private String name;
    /** 品牌名（C端展示）。 */
    private String brandName;
    /** ENABLED/SUSPENDED。 */
    private String status;
    private String plan;
    /** 配额 JSON 文本。 */
    private String quota;
    /** 联系方式（掩码；明文落 pb_pii）。 */
    private String contact;
    private String expireAt;
}
