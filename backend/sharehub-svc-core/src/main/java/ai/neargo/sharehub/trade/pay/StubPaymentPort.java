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
        return new PayResult(req.payNo(), ref("STUBTXN"), PayOrderStatus.PAID.name(), req.amount(), req.currency(), null);
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
