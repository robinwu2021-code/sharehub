package ai.neargo.sharehub.platform.md.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多国家市场（md_market_country）—— 开城清单，币种/时区/合规口径的源头。
 *
 * <p>全局表：{@code country_code}（ISO alpha-2）是自然键，不走前缀取号。
 *
 * <p><b>{@code cityCount} 故意不落列</b>（[db-design §1.4]「计数列不是列，是聚合」）——
 * 出参里的已开城市数由 {@code md_region} 下挂的子节点实时聚合，
 * 避免「维护列与明细打架」。见 {@code MarketServiceImpl#toVO}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
// 全局表：无 tenant_id 列（db-design §1.3 全局表清单 tenant/iam_permission/dict_/md_/gw_vendor）。
// 不排除会让 MyBatis-Plus 拼出不存在的列 —— 这类不一致在「没人查过这张表」时不会暴露，
// 一旦有代码第一次 SELECT 它就是 Unknown column（price_rule 就是这么炸的）。
@TableName(value = "md_market_country", excludeProperty = "tenantId")
public class MdMarketCountry extends BaseEntity {
    private String countryCode;
    private String name;
    private String nameEn;
    private String nameAr;
    private String currency;
    /** IANA 时区，如 {@code Asia/Dubai}。 */
    private String timezone;
    /** 合规口径标签，如 {@code PDPL}/{@code CBUAE}。 */
    private String compliance;
    /** LIVE/PILOT/PLANNED。 */
    private String status;
}
