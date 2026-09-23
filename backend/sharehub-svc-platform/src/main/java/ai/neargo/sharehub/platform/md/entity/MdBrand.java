package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 品牌（md_brand）—— 运营方对 C 端呈现的**经营身份**：名称、Logo、客服、协议。
 *
 * <h3>两条边界（2026-09-23 定，领域模型 §五）</h3>
 * <ol>
 *   <li><b>品牌归运营方，代理商不得拥有</b>。代理商用运营方的品牌，C 端无感知 ——
 *       所以本表没有 {@code agent_no}，将来也不该有。</li>
 *   <li><b>品牌是呈现层，不是隔离层</b>：设备网络与用户账户全平台共享，
 *       异地归还跨品牌照常。做成隔离层等于把 ADR-026 刚砍掉的租户换个名字建回来
 *       （历史上 {@code tenant.brand_name} 就是这么来的）。</li>
 * </ol>
 *
 * <p>站点侧是 {@code loc_site.brand_no} <b>一列</b>而不是关系表 —— 一站一品牌是硬约束：
 * 分成、坪效、工单都按站点统计，一站两品牌会让「这笔钱算哪个品牌的」没有答案。
 *
 * <p>全局表，{@code tenant_id} 被排除 —— 见 {@link MdBank} 的详细说明。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "md_brand", excludeProperty = "tenantId")
public class MdBrand extends BaseEntity implements ai.neargo.sharehub.common.crud.Archivable {

    /** 业务键，前缀 {@code BR}。 */
    private String brandNo;

    private String name;
    private String nameEn;
    private String nameAr;

    /** C 端展示的 Logo。 */
    private String logoUrl;

    /** 该品牌的客服电话（C 端「联系客服」用）。 */
    private String supportPhone;

    /** 归属市场；**本期不校验**，待 S2 的区域 → 市场链路。 */
    private String marketCode;

    /** ENABLED / DISABLED。 */
    private String status;

    /** 归档时间；null = 在用。**不是 deleted** —— 归档是业务停用、可恢复。 */
    private java.time.LocalDateTime archivedAt;
}
