package ai.neargo.sharehub.finance;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@link WithdrawFeePolicy} 的骨架实现 —— <b>读硬编码默认值，不是最终口径</b>。
 *
 * <p>真实口径在 {@code sys_biz_rule(category='WITHDRAW')}（[db-design §5.5]），本子域无该表访问权。
 * 待 platform 域的业务规则服务就位后，新增一个读该表的 {@code @Primary} 实现即可顶替本类，
 * 调用方（{@code WithdrawalServiceImpl}）一行不改。
 *
 * <p>默认口径：费率 {@code 0.006}（0.6%），下限 {@code 2}、上限 {@code 50}，向上取整到分。
 * 这三个数字是**占位**，上线前必须由财务确认并迁到配置表。
 */
@Component
public class StubWithdrawFeePolicy implements WithdrawFeePolicy {

    /** 占位默认值，真实值见 {@code sys_biz_rule(category='WITHDRAW')}。 */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.0060");
    private static final BigDecimal DEFAULT_MIN = new BigDecimal("2.00");
    private static final BigDecimal DEFAULT_MAX = new BigDecimal("50.00");

    @Override
    public BigDecimal feeOf(BigDecimal amount, String currency, String payeeType) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // 手续费向上取整到分：舍入误差归平台不归收款方，避免到账额比承诺少一分
        BigDecimal fee = amount.multiply(DEFAULT_RATE).setScale(2, RoundingMode.CEILING);
        if (fee.compareTo(DEFAULT_MIN) < 0) fee = DEFAULT_MIN;
        if (fee.compareTo(DEFAULT_MAX) > 0) fee = DEFAULT_MAX;
        // 费不得吃掉本金：小额提现时上限收敛到申请额
        return fee.min(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
