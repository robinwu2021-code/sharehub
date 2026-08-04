package ai.neargo.sharehub.loc.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 门店生命周期（loc_site_lifecycle，[db-design §3.4]）。
 *
 * <p>**一站点一行**（UK(site_no)），阶段流转 in-place 更新，每次流转必须留痕到
 * {@link LocSiteLifecycleLog}（append 表）—— 本表只保留「当前阶段」，历史在 log 里。
 *
 * <p>⚠️ {@link #gmvLtm} 是 [db-design §3.4] 明文标注的**唯一「聚合值落列」例外**，
 * 与 §1.4「计数列不是列」相抵。保留的理由是它属于**阶段决策快照**（"退场时该站累计做了多少"），
 * 不是实时经营指标：**由阶段流转时写入，不做定时回刷**。要实时值请查站点坪效
 * （{@code GET /api/ops/site-analysis}，读模型，不落表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("loc_site_lifecycle")
public class LocSiteLifecycle extends BaseEntity {

    /** 站点业务键；本表的逻辑主键（UK）。 */
    private String siteNo;

    /** PROSPECTING / SIGNED / LIVE / ACTIVE / CHURNED / CLOSED。 */
    private String stage;

    /** 进入当前阶段的日期 {@code yyyy-MM-dd}（DDL DATE）。 */
    private String stageAt;

    /** 负责人 employee_no。 */
    private String ownerNo;

    /** 负责人展示名（写入时快照，不随源改名回溯，[db-design §1.4]）。 */
    private String owner;

    /** 阶段决策快照：进入当前阶段时的近 12 月 GMV。**不定时回刷**。 */
    private BigDecimal gmvLtm;

    private String currency;
}
