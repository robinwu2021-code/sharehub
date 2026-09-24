package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos;
import ai.neargo.sharehub.finance.entity.ShareRule;

/**
 * 分润服务：规则（{@code share_rule}）+ 明细（{@code share_record}）+ 统计（读模型，无表）。
 *
 * <p><b>一张表 + 维度切换器</b>：{@code dimension=VENUE|AGENT} 切主体，不是两套接口两张表
 * （[db-design §5.4]「比竞品清晰在哪」）。
 */
public interface ShareService {

    /** 分润明细分页。{@code dimension}/{@code status} 可空表示不过滤。 */
    PageResult<FinDtos.ShareRecord> pageRecords(Integer page, Integer size, String keyword,
                                                String dimension, String status);

    /**
     * 分润统计（{@code GET /api/trade/share-summaries}）—— 由 {@code share_record}
     * 按 (dimension, payeeNo, period) 聚合，**不建表**（[db-design §12.3]）。
     *
     * @param sortKey 受控排序列，**仅允许 {@code shareAmount|pendingAmount|gmv|orderCount}**
     *                （[api/README §1 受控排序]）；其它值一律抛异常拒绝，绝不拼进 SQL
     * @param sortDir {@code asc} / {@code desc}，同样白名单校验
     */
    PageResult<FinDtos.ShareSummary> summaries(Integer page, Integer size, String dimension,
                                               String period, String sortKey, String sortDir);

    /** 规则 upsert：{@code ruleNo} 为空则取号新建（前缀 {@code SR}），否则按业务键更新。 */
    FinDtos.ShareRule saveRule(FinDtos.ShareRuleReq req);

    /** 分润规则分页。{@code keyword} 匹配规则号/收款方编号/收款方名。 */
    PageResult<FinDtos.ShareRule> pageRules(Integer page, Integer size, String keyword);
}
