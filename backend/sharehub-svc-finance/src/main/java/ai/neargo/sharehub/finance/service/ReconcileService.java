package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.finance.dto.FinDtos.ReconDiffRow;
import ai.neargo.sharehub.finance.dto.FinDtos.Reconcile;

import java.util.List;

/**
 * 对账服务（{@code recon_task} + {@code recon_diff}）—— <b>三方核对</b>：
 * nearpay 渠道账单 ↔ 支付引用 {@code pay_order} ↔ 账务分录 {@code acct_ledger}。
 *
 * <p>两方对平只能证明「渠道和我们记的一样」，三方才能同时抓出「钱到了但没记账」
 * 和「记了账但钱没到」两类问题。
 */
public interface ReconcileService {

    /** 对账批次分页。 */
    PageResult<Reconcile> pageTasks(Integer page, Integer size, String keyword, String status, String period);

    /** 某批次的差错明细。 */
    List<ReconDiffRow> diffs(String batchNo);

    /**
     * 差错平账处置：把差错标记为已处置（{@code resolved=1}），并在批次上落处置留痕
     * （{@code handle_status/result/note/by/at}，V31 —— 此前页面传的处置分类与说明被静默丢弃）。
     *
     * <p><b>只标记，不改任何历史分录</b> —— 账务侧的修正必须由新的红冲分录表达。
     *
     * @param diffId     差错行 id；为空则处置该批次下全部未处置差错
     * @param action     处置动作 verify/platform/channel/compensate（前端四个按钮），映射 handle_result
     * @param handleNote 处置说明，<b>必填</b>（金额/凭证号/对接人）
     * @param operator   处置人；空则记当前登录人
     * @return 处置后的批次行（契约 {@code handleRecon(): Promise<Reconcile>}）
     */
    Reconcile resolve(String batchNo, Long diffId, String action, String handleNote, String operator);

    /** 对账页头统计：任务数 / 差异数 / 已处理数。前端用它做「还有多少没对平」的概览。 */
    java.util.Map<String, Object> stats(String period);
}
