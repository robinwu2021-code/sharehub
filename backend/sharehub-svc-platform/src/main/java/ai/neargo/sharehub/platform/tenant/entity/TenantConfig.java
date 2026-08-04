package ai.neargo.sharehub.platform.tenant.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户配置（tenant_config）—— 🔒 休眠口子。
 *
 * <p>{@code (tenant_no, category, config_key)} 复合 UK。{@code category} ∈
 * {@code PAY}/{@code BILLING}/{@code BRAND}/{@code VENDOR}，{@code configValue} 是 JSON 文本。
 *
 * <p>刻意做成**通用键值**而不是按前端 {@code TenantConfig} 拍成固定列：
 * 休眠期的表最怕「为一个还没上线的形态提前定死列」，加一项配置就得改表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "tenant_config", excludeProperty = "tenantId")
public class TenantConfig extends BaseEntity {
    private String tenantNo;
    /** PAY/BILLING/BRAND/VENDOR。 */
    private String category;
    private String configKey;
    /** 配置值 JSON 文本。 */
    private String configValue;
}
