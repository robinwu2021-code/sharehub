package ai.neargo.sharehub.trade.pay.service.impl;

import ai.neargo.sharehub.trade.pay.PayBizKey;
import ai.neargo.sharehub.trade.pay.PaymentPort;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayAuthEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayRefundEntry;
import ai.neargo.sharehub.trade.pay.entity.PayAuth;
import ai.neargo.sharehub.trade.pay.entity.PayOrder;
import ai.neargo.sharehub.trade.pay.entity.PayRefund;
import ai.neargo.sharehub.trade.pay.mapper.PayAuthMapper;
import ai.neargo.sharehub.trade.pay.mapper.PayOrderMapper;
import ai.neargo.sharehub.trade.pay.mapper.PayRefundMapper;
import ai.neargo.sharehub.trade.pay.service.PaymentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * 支付编排实现。**业务代码只依赖 {@link PaymentPort} 接口**，永远不 new 具体实现，
 * 也不在这里出现任何渠道密钥（ADR-005：明文在 KMS/vault）。
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    private static final String TENANT_MAIN = "MAIN";

    private final PayOrderMapper payMapper;
    private final PayAuthMapper authMapper;
    private final PayRefundMapper refundMapper;
    private final PaymentPort port;

    public PaymentServiceImpl(PayOrderMapper payMapper, PayAuthMapper authMapper,
                              PayRefundMapper refundMapper, PaymentPort port) {
        this.payMapper = payMapper;
        this.authMapper = authMapper;
        this.refundMapper = refundMapper;
        this.port = port;
    }

    @Override
    @Transactional
    public PayOrderEntry pay(String orderNo, String cUserNo, String type, BigDecimal amount,
                             String currency, String channelCode, String idempotencyKey) {
        require(orderNo, "orderNo");
        require(type, "type");
        requirePositive(amount, "amount");

        PayOrder e = new PayOrder();
        e.setTenantId(TENANT_MAIN);
        e.setPayNo(nextNo(payMapper, PayBizKey.PAY_ORDER, "pay_no", PayOrder::getPayNo));
        e.setOrderNo(orderNo);
        e.setCUserNo(cUserNo);
        e.setType(type);
        e.setAmount(amount);
        e.setCurrency(currency == null || currency.isBlank() ? "AED" : currency);
        e.setChannelCode(channelCode);
        e.setStatus("INIT");
        payMapper.insert(e);

        PaymentPort.PayResult r = port.pay(new PaymentPort.PayRequest(
                e.getPayNo(), orderNo, cUserNo, type, e.getAmount(), e.getCurrency(), channelCode, idempotencyKey));

        e.setStatus(r.status());
        e.setNearpayTxnNo(r.txnRef());
        payMapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public PayAuthEntry preAuth(String orderNo, String cUserNo, BigDecimal freezeAmount,
                                String currency, String channelCode, String idempotencyKey) {
        require(orderNo, "orderNo");
        requirePositive(freezeAmount, "freezeAmount");

        PayAuth e = new PayAuth();
        e.setTenantId(TENANT_MAIN);
        e.setAuthNo(nextNo(authMapper, PayBizKey.PAY_AUTH, "auth_no", PayAuth::getAuthNo));
        e.setOrderNo(orderNo);
        e.setCUserNo(cUserNo);
        e.setFreezeAmount(freezeAmount);
        e.setCapturedAmount(BigDecimal.ZERO);
        e.setStatus("FROZEN");
        authMapper.insert(e);

        PaymentPort.AuthResult r = port.preAuth(new PaymentPort.AuthRequest(
                e.getAuthNo(), orderNo, cUserNo, freezeAmount, currency, channelCode, idempotencyKey));

        e.setStatus(r.status());
        e.setNearpayAuthNo(r.authRef());
        authMapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public PayAuthEntry capture(String authNo, BigDecimal amount, String idempotencyKey) {
        PayAuth e = mustAuth(authNo);
        if (!"FROZEN".equals(e.getStatus())) {
            throw new IllegalStateException("仅 FROZEN 可请款，当前：" + e.getStatus());
        }
        requirePositive(amount, "amount");
        if (amount.compareTo(e.getFreezeAmount()) > 0) {
            throw new IllegalArgumentException("请款额超过冻结额，超额部分须另开支付单");
        }

        PaymentPort.AuthResult r = port.capture(e.getNearpayAuthNo(), amount, idempotencyKey);
        e.setStatus(r.status());
        e.setCapturedAmount(amount);
        authMapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public PayAuthEntry release(String authNo, String idempotencyKey) {
        PayAuth e = mustAuth(authNo);
        if (!"FROZEN".equals(e.getStatus())) {
            throw new IllegalStateException("仅 FROZEN 可释放，当前：" + e.getStatus());
        }
        PaymentPort.AuthResult r = port.release(e.getNearpayAuthNo(), idempotencyKey);
        e.setStatus(r.status());
        authMapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public PayRefundEntry refund(String payNo, String ordRefundNo, BigDecimal amount,
                                 String reason, String idempotencyKey) {
        // ord_refund 是业务审批单，pay_refund 是渠道执行凭证；没有审批单就退款 = 绕过审批链
        require(ordRefundNo, "ordRefundNo（须先有已审批的 ord_refund）");
        PayOrder pay = payMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getPayNo, payNo).last("limit 1"));
        if (pay == null) throw new IllegalArgumentException("支付单不存在: " + payNo);
        if (!"PAID".equals(pay.getStatus())) {
            throw new IllegalStateException("仅 PAID 的支付单可退款，当前：" + pay.getStatus());
        }
        requirePositive(amount, "amount");
        if (amount.compareTo(pay.getAmount()) > 0) {
            throw new IllegalArgumentException("退款额超过原支付额");
        }

        PayRefund e = new PayRefund();
        e.setTenantId(TENANT_MAIN);
        e.setRefundNo(nextNo(refundMapper, PayBizKey.PAY_REFUND, "refund_no", PayRefund::getRefundNo));
        e.setPayNo(payNo);
        e.setOrdRefundNo(ordRefundNo);
        e.setAmount(amount);
        e.setReason(reason);
        e.setStatus("INIT");
        refundMapper.insert(e);

        PaymentPort.RefundResult r = port.refund(new PaymentPort.RefundRequest(
                e.getRefundNo(), payNo, pay.getNearpayTxnNo(), amount, reason, idempotencyKey));

        e.setStatus(r.status());
        e.setNearpayRefundNo(r.refundRef());
        refundMapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public void mirrorPayStatus(String txnRef, String status, String paidAt) {
        PayOrder e = payMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getNearpayTxnNo, txnRef).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("未知 nearpay 交易引用: " + txnRef);
        if ("PAID".equals(e.getStatus()) && !"PAID".equals(status)) {
            throw new IllegalStateException("已支付的单不接受回退到 " + status);
        }
        e.setStatus(status);
        if (paidAt != null) e.setPaidAt(paidAt);
        payMapper.updateById(e);
    }

    @Override
    public PayOrderEntry getPay(String payNo) {
        PayOrder e = payMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getPayNo, payNo).last("limit 1"));
        return e == null ? null : toVO(e);
    }

    @Override
    public PayAuthEntry getAuth(String authNo) {
        PayAuth e = authMapper.selectOne(new LambdaQueryWrapper<PayAuth>()
                .eq(PayAuth::getAuthNo, authNo).last("limit 1"));
        return e == null ? null : toVO(e);
    }

    // ——————————————————————— 内部 ———————————————————————

    private PayAuth mustAuth(String authNo) {
        PayAuth e = authMapper.selectOne(new LambdaQueryWrapper<PayAuth>()
                .eq(PayAuth::getAuthNo, authNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("预授权单不存在: " + authNo);
        return e;
    }

    /**
     * 取号：扫描同前缀最大号 + 1（[db-design §1.4.1]，**禁止**「前缀 + 数组长度」）。
     * 并发下仍可能撞号，由业务键 UNIQUE 兜底。
     */
    private <T> String nextNo(BaseMapper<T> mapper, String prefix, String keyColumn, Function<T, String> keyOf) {
        T top = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T>()
                .likeRight(keyColumn, prefix)
                .orderByDesc(keyColumn)
                .last("limit 1"));
        long n = 0L;
        if (top != null) {
            String k = keyOf.apply(top);
            if (k != null && k.length() > prefix.length()) {
                String digits = k.substring(prefix.length()).replaceAll("\\D", "");
                if (!digits.isEmpty()) {
                    try {
                        n = Long.parseLong(digits);
                    } catch (NumberFormatException ignore) {
                        // 历史脏号不参与取号，UNIQUE 兜底
                    }
                }
            }
        }
        return prefix + String.format("%06d", n + 1);
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private static void requirePositive(BigDecimal v, String name) {
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException(name + " 必须为正数");
    }

    private static PayOrderEntry toVO(PayOrder e) {
        return new PayOrderEntry(e.getPayNo(), e.getOrderNo(), e.getCUserNo(), e.getType(), e.getAmount(),
                e.getCurrency(), e.getChannelCode(), e.getStatus(), e.getNearpayTxnNo(), e.getPaidAt());
    }

    private static PayAuthEntry toVO(PayAuth e) {
        return new PayAuthEntry(e.getAuthNo(), e.getOrderNo(), e.getCUserNo(), e.getFreezeAmount(),
                e.getCapturedAmount(), e.getStatus(), e.getNearpayAuthNo(), e.getExpireAt());
    }

    private static PayRefundEntry toVO(PayRefund e) {
        return new PayRefundEntry(e.getRefundNo(), e.getPayNo(), e.getOrdRefundNo(), e.getAmount(),
                e.getReason(), e.getStatus(), e.getNearpayRefundNo());
    }
}
