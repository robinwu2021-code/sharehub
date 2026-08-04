package ai.neargo.sharehub.finance.service;

import ai.neargo.sharehub.finance.dto.FinDtos.Settlement;
import ai.neargo.sharehub.finance.dto.FinDtos.SettlementView;

import java.util.List;

/**
 * 结算服务（{@code stl_settlement} + {@code stl_settlement_detail}）。
 *
 * <p><b>无人工创建入口</b>（[api/README §六·A]）：结算单只能由 {@link #generate} 出账，
 * 由调度作业经 {@code POST /internal/trade/settlements/generate} 触发。
 * 运营端能做的只有查看详情与 {@link #confirm}。
 */
public interface SettlementService {

    /** 结算单分页。{@code keyword} 匹配结算单号/收款方；{@code status} 可空。 */
    ai.neargo.common.core.PageResult<Settlement> page(Integer page, Integer size, String keyword, String status);

    /** 结算单详情 + 明细。不存在抛 {@link IllegalArgumentException}。 */
    SettlementView detail(String settleNo);

    /** 确认结算单：GEN → CONFIRMED，非法迁移由 {@code SettlementStateMachine} 拒。 */
    SettlementView confirm(String settleNo);

    /**
     * 周期出账（内部批处理）：把指定账期内 {@code status=PENDING} 的分润记录按收款方汇总成结算单，
     * 明细逐条落 {@code stl_settlement_detail}，并把 {@code share_record.settle_no} 回填、置 DONE。
     *
     * <p><b>幂等</b>：同一 (payeeNo, period) 已出过账则跳过，不重复出单 —— 批处理重跑是常态。
     *
     * @param period 账期 {@code YYYY-MM}
     * @return 本次新生成的结算单号
     */
    List<String> generate(String period, String payeeType);
}
