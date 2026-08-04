package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户券（usr_coupon，[db-design §6.3]）—— 券模板发放给某个 C 端用户后的实例。
 *
 * <p><b>属主表</b>：[db-design §10] 明确 usr_coupon 走 {@code (tenant_id, c_user_no, created_at)} 索引，
 * 一切查询必须能按 {@code cUserNo} 过滤；C 端读取时 {@code cUserNo} 只能取自
 * {@code ConsumerContext}，不信前端传参（防 IDOR）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_coupon")
public class UsrCoupon extends BaseEntity {

    private String couponNo;

    /** 属主 C 端用户号。 */
    private String cUserNo;

    /** 来源券模板 {@link CouponTpl#getTplNo()}。 */
    private String tplNo;

    /** UNUSED / USED / EXPIRED。 */
    private String status;

    /** 核销时写入的租借订单号；空 = 未使用。 */
    private String usedOrderNo;

    /** 过期时刻（[db-design §1.5] 时间列在实体侧统一用 String，避免序列化格式分歧）。 */
    private String expireAt;
}
