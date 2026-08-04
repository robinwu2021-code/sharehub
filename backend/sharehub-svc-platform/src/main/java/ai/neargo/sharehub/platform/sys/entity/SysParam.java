package ai.neargo.sharehub.platform.sys.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统参数（sys_param）—— 键值型运营开关，如 {@code order.max_duration_min}。
 *
 * <p><b>非全局表</b>：唯一键是 {@code (tenant_id, param_key)} 复合 UK，
 * 不是单列 {@code param_key}（[db-design §2.1 注]）。因此 service 的按键查询
 * 必须同时带 tenantId —— 见 {@code SysParamServiceImpl#selectByKey}。
 *
 * <p>{@code value} 一律字符串存储，由消费方自行解析类型（不给字典表塞多态列）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_param")
public class SysParam extends BaseEntity {
    private String paramKey;
    private String label;
    private String value;
    /** 分组，运营端分栏用。 */
    private String groupName;
}
