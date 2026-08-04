package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 登录设置（sys_login_setting）—— 按国家配置 C 端可用登录方式（MENA 各国监管差异大）。
 *
 * <p>{@code country = '*'} 是**默认行**：端上先查本国，查不到回落 {@code *}。
 *
 * <p><b>非全局表</b>：UK 是 {@code (tenant_id, country)} 复合键，按键查询必须带 tenantId。
 *
 * <p><b>保存时校验：至少保留一种登录方式</b>（otp/password/apple/google 不能全关）——
 * 全关等于把所有用户锁在门外，且没有任何页面能救回来，故在 service 层硬拦。
 *
 * <p>布尔列一律 {@code Integer}（TINYINT(1)），1=开。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_setting")
public class SysLoginSetting extends BaseEntity {
    /** ISO alpha-2；{@code *} = 默认行。 */
    private String country;
    private String countryName;
    private Integer otpEnabled;
    private Integer passwordEnabled;
    private Integer appleEnabled;
    private Integer googleEnabled;
    private Integer otpExpireSec;
    private Integer otpDailyLimit;
    /** 强制实名（沙特等合规要求国家）。 */
    private Integer forceRealName;
}
