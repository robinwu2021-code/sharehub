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
     * @param payeeNos 只给这几个收款方出账；null/空 = 该类型全部。
     *                 <b>此前这个参数不存在</b>，而运营端出账抽屉有个「选择要出账的对象」多选
     *                 （帮助文案还写着「该周期没有分润明细的对象会被拒绝」）——
     *                 前端把选中的 payeeNos 发过来，后端整个忽略，于是**勾了三个场地方，
     *                 出的是这个类型全部收款方的账**。结算单是钱，多出来的那些还要人去撤。
     * @return 本次新生成的结算单号
     */
    List<String> generate(String period, String payeeType, java.util.List<String> payeeNos);
}
