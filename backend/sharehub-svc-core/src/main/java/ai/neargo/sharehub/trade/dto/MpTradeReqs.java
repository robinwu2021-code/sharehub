package ai.neargo.sharehub.trade.dto;

/**
 * C 端借还/支付的写入参（裸 {@code Map} → record，2026-09-26；见 backend/known-map-request-bodies.txt）。
 *
 * <p>裸 Map 当请求体 = 这个端点没有契约：契约抽取抽不出形状，拼错的键静默忽略。
 */
public final class MpTradeReqs {

    private MpTradeReqs() {
    }

    /**
     * 扫码借出入参。
     *
     * @param useFreeDeposit 缺省视为 {@code true} —— 确认页默认就是免押（{@code useFree = ref(true)}），
     *                       老客户端不传时按它当时的界面语义走
     * @param couponNo       用券；传一张用不了的券会**拒单**而不是静默按原价
     */
    public record RentReq(String cabinetNo, Boolean useFreeDeposit, String couponNo) {

        /** 缺省免押。用 {@code Boolean} 而非 {@code boolean} 才区分得出「没传」与「传了 false」。 */
        public boolean freeDeposit() {
            return useFreeDeposit == null || useFreeDeposit;
        }
    }

    /** 免押预授权入参。 */
    public record DepositFreeReq(String cabinetNo) {
    }

    /**
     * 按订单支付入参。
     *
     * <p><b>刻意不收 amount</b>。此前这里收：
     * <pre>
     *   amount = body.get("amount") == null ? 订单应付 : new BigDecimal(body.get("amount"))
     * </pre>
     * 而 {@code PaymentServiceImpl.pay} 只做 {@code requirePositive(amount)}，
     * <b>从不与订单应付比对</b> —— 于是 {@code POST /mp/trade/pay {orderNo, amount: 0.01}}
     * 就能给一张应付 17 的单记一笔 0.01 的支付；幂等键是 {@code "PAY-" + orderNo}，
     * <b>先到的那笔锁死这张单的支付金额</b>。
     *
     * <p>隔壁 {@code MpUserController.recharge} 的类注释把这条规矩写得很清楚：
     * 「收前端传的金额，『充 1 元到账 100』就是一次普通的改参数请求」——
     * 同一个仓库里，充值守住了，按单支付没守。
     * 金额一律由服务端从订单上取。
     *
     * @param scene 支付用途；按 {@code pay_order.type} 的词表（DEPOSIT/RENT/BUYOUT）归一，
     *              缺省 {@code RENT}。此前缺省写的是 {@code "ORDER"} —— 那个值**不在词表里**，
     *              而 c-app 发的又是小写的 {@code rent/deposit/…}，于是这一列三种写法混着落。
     */
    public record PayReq(String orderNo, String scene) {

        private static final java.util.Set<String> SCENES = java.util.Set.of("DEPOSIT", "RENT", "BUYOUT");

        /** 归一到 {@code pay_order.type} 的词表；不认识的值拒而不是悄悄落进去。 */
        public String payType() {
            if (scene == null || scene.isBlank()) return "RENT";
            String s = scene.trim().toUpperCase();
            if (!SCENES.contains(s)) {
                throw ai.neargo.sharehub.common.BizException.badRequest("error.pay.scene_invalid", scene);
            }
            return s;
        }
    }
}
