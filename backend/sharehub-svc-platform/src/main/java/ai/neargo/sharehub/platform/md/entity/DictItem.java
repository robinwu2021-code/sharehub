package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 参数字典（dict_item）—— 运营端「系统设置 › 基础字典 › 参数字典」的数据源。
 *
 * <p>全局表（[db-design §2.4]）：业务键 {@code dict_no} 走前缀取号 {@code DC}
 * （{@code group_code + code} 虽是自然键，但复合键不便做行键/编辑，故另立单号，与前端 {@code DictEntry} 一致）。
 *
 * <p><b>字段以 db-design §2.4 为准</b>：{@code dict_no / group_code / code / label / label_en /
 * label_ar / sort / enabled}。老脚本 {@code pb_core-user-ad-workorder.sql} 里的 v1 版本
 * （{@code dict_type/dict_key/dict_value/value_ar/status}）字段名与之不符，已在交付报告里列为待对齐项。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "dict_item", excludeProperty = "tenantId")
public class DictItem extends BaseEntity {
    private String dictNo;
    /** 分组码，如 {@code order_status}/{@code wo_type}（前端 VO 里叫 {@code group}）。 */
    private String groupCode;
    private String code;
    private String label;
    private String labelEn;
    private String labelAr;
    private Integer sort;
    /** TINYINT(1)：1=启用。 */
    private Integer enabled;
}
