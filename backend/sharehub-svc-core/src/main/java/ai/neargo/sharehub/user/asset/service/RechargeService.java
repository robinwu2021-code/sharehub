package ai.neargo.sharehub.user.asset.service;

import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargeResultVO;

/**
 * C 端钱包充值（C-WA-02）。
 *
 * <h2>为什么要有这一层</h2>
 * 充值套餐（{@code usr_recharge_pkg}）、充值单（{@code usr_recharge_order}）、钱包与流水
 * 此前都已经建好，但**没有任何地方会创建充值单** —— 运营端那份是只读列表，
 * C 端 {@code POST /mp/trade/pay} 在没有 orderNo 时直接抛
 * 「充值等无单支付待充值单流程接入」。于是钱包页的「充值」按钮点下去必然失败，
 * 而 c-app 那边没有 catch，表现是**什么都不发生**：没有提示，也没有报错。
 *
 * <p>同时这也意味着 {@code usr_wallet} 里的钱只可能来自运营手工调账 ——
 * 「累计充值金额」这个经营指标恒为 0。
 */
public interface RechargeService {

    /**
     * 按套餐充值并即时结算（桩通道，ADR-005 不真扣款）。
     *
     * <p><b>金额一律取自套餐</b>，不接受调用方传金额 —— 采信前端传值等于把定价权交给调用方，
     * 「充 1 元到账 100」就是一次普通的改参数请求。
     *
     * <p>成功后：充值单置 {@code PAID}，钱包按 {@code payAmount + giftAmount} 入账，
     * 本金与赠额各记一条流水（{@code bizNo} = 充值单号，对账时可反查）。
     * 通道未即时成功则单据留在 {@code PENDING}，钱包不动。
     *
     * @throws IllegalArgumentException 套餐不存在 / 已停用
     */
    RechargeResultVO recharge(String cUserNo, String packageNo);
}
