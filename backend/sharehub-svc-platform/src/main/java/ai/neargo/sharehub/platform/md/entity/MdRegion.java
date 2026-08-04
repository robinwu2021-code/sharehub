package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 地区库（md_region）—— 国家/城市/商圈三级行政树，是市场、场地、辖区的公共坐标系。
 *
 * <p>全局表：{@code region_id} 是**自然键**（如 {@code AE}/{@code AE-DU}/{@code DU-MAR}），不走前缀取号。
 * {@code parent_id} 自引用（逻辑外键，不建物理 FK）。
 *
 * <p>{@code city_count} 在本表是**落列**的维护列（[db-design §2.4] 关键列有它）；
 * 而 {@code md_market_country.city_count} <b>不落列</b>，由本表聚合得出
 * （见 {@code MarketServiceImpl}）—— 两者口径不同，别互抄。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "md_region", excludeProperty = "tenantId")
public class MdRegion extends BaseEntity {
    private String regionId;
    private String name;
    private String nameEn;
    private String nameAr;
    /** 上级 region_id；顶级为 null。 */
    private String parentId;
    /** 1=国家 2=城市 3=商圈。 */
    private Integer level;
    private Integer cityCount;
}
