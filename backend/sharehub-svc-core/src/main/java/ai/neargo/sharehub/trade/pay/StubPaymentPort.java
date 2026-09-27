package ai.neargo.sharehub.trade.pay;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PaymentPort} 的 MVP 桩实现：**一律模拟成功**，不发任何外部请求。
 *
 * <p>存在的理由（ADR-005）：nearpay 集成延后，但借还闭环、押金编排、退款审批链现在就要能跑通。
 * 桩把「对侧引用号」造出来，上层的状态机与幂等逻辑就能被完整验证。
 *
 * <p><b>换成真实实现时</b>：新增 {@code NearpayPaymentPort} 并给它 {@code @Primary}（或给本桩加
 * {@code @ConditionalOnProperty}），注入点一行不改 —— 上层只认 {@link PaymentPort} 接口。
 *
 * <p><b>桩不做的事</b>：不校验余额、不模拟失败/超时、不产生回调。要测失败路径请写测试替身，
 * 不要往这里加开关 —— 桩一旦有分支就会被当成业务逻辑用。
 */
@Service
public class StubPaymentPort implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentPort.class);

    /** 桩引用号自增段，仅进程内唯一，够用于本地/联调。 */
    private final AtomicLong seq = new AtomicLong(1);

    /**
     * 本桩<b>是唯一的 {@link PaymentPort} 实现</b>且无条件装配，所以它会被带上任何环境
     * 而不产生任何症状 —— 照 {@code DevMode} 的做法在启动日志里喊一声。
     *
     * <p>不做成「缺配置就起不来」是因为它现在是唯一实现：那等于所有环境都起不来。
     * 真正的闸做在**会把钱变成余额的那条路上**（见 {@link #simulated()} 与
     * {@code RechargeServiceImpl}）。接入真实通道后本类降级为测试用，这条横幅随之消失。
     */
    @PostConstruct
    void warn() {
        log.warn("════════════════════════════════════════════════════════════");
        log.warn("  支付通道是**桩实现**（StubPaymentPort）：一律模拟成功，不发外部请求。");
        log.warn("  借还/押金/买断的「已支付」都不代表真收到钱。");
        log.warn("  充值（会变成可花的余额）在 dev-mode 关闭时已被拒绝，见 RechargeServiceImpl。");
        log.warn("  接入 nearpay 后新增 NearpayPaymentPort 并给它 @Primary，本行随之消失。");
        log.warn("════════════════════════════════════════════════════════════");
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public PayResult pay(PayRequest req) {
        if (req.orderNo() != null && declineNext.remove(req.orderNo())) {
            return new PayResult(req.payNo(), null, PayOrderStatus.FAILED.name(), req.amount(), req.currency(),
                    "STUB_DECLINED");
        }
        return new PayResult(req.payNo(), ref("STUBTXN"), PayOrderStatus.PAID.name(), req.amount(), req.currency(), null);
    }

    /** 被指定「下一次请款失败」的订单号，用一次即清。 */
    private final java.util.Set<String> declineNext = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * <b>测试用</b>：让该订单的下一次 {@link #pay} 返回 FAILED。
     *
     * <p>桩恒成功，于是「请款失败 → 欠费 → 拦下次借出 → 补缴」这整条路径没法被触发
     * （见 ConsumerArrearsTest）。没有 HTTP 入口；接 nearpay 后本类整体替换，钩子随之消失。
     */
    public void declineNextFor(String orderNo) {
        declineNext.add(orderNo);
    }

    @Override
    public AuthResult preAuth(AuthRequest req) {
        return new AuthResult(req.authNo(), ref("STUBAUTH"), PayAuthStatus.FROZEN.name(),
                req.freezeAmount(), BigDecimal.ZERO, null);
    }

    @Override
    public AuthResult capture(String authRef, BigDecimal amount, String idempotencyKey) {
        return new AuthResult(null, authRef, PayAuthStatus.CAPTURED.name(), amount, amount, null);
    }

    @Override
    public AuthResult release(String authRef, String idempotencyKey) {
        return new AuthResult(null, authRef, PayAuthStatus.RELEASED.name(), BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        return new RefundResult(req.refundNo(), ref("STUBRFD"), PayRefundStatus.SUCCESS.name(), req.amount(), null);
    }

    @Override
    public PayResult query(String txnRef) {
        return new PayResult(null, txnRef, PayOrderStatus.PAID.name(), null, null, null);
    }

    private String ref(String prefix) {
        return prefix + String.format("%08d", seq.getAndIncrement());
    }
}
