package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 银行字典（md_bank）—— 提现收款方的合法银行清单。
 *
 * <p>全局表：{@code bank_code} 是**自然键**（对外有语义），不走前缀取号。
 * {@code ibanLength} 供提现申请时校验账号长度（[db-design §2.4]）。
 *
 * <p><b>⚠️ 全局表继承 BaseEntity 的处理（本项目通用约定，所有无 {@code tenant_id} 列的表照此办理）</b>：
 * {@link BaseEntity} 带 {@code tenantId} 字段，但全局表（{@code md_*} / {@code dict_item} /
 * {@code dev_alarm_code} / {@code gw_vendor} / {@code iam_permission}）在 DDL 里**没有这一列**
 * —— 直接继承会让 MyBatis-Plus 把 {@code tenant_id} 拼进 INSERT/UPDATE，运行期报 Unknown column。
 * 用 {@code @TableName(excludeProperty = "tenantId")} 排除该属性，
 * <b>而不是</b>去改 {@code BaseEntity}（它被全域实体继承，动它波及面过大）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "md_bank", excludeProperty = "tenantId")
public class MdBank extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {
    private String bankCode;
    private String bankName;
    private String bankNameEn;
    private String bankNameAr;
    private String country;
    private String currency;
    private String swiftPrefix;
    private Integer ibanLength;
    private String status;

    /** 归档时间；null=在用。**不是 deleted** —— 归档是业务停用、可恢复，见 Archivable。 */
    private java.time.LocalDateTime archivedAt;
}
