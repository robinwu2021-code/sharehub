package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 账户（acct_account，[db-design §5.4]）—— 复式记账的科目载体。
 *
 * <p>{@code ownerType} 四类主体：PLATFORM（平台自有）/ VENUE（场地方）/ AGENT（代理商）/ USER（C 端钱包），
 * {@code ownerNo} 指向对应主体业务键（{@code VEN…} / {@code AG…} / {@code U…}）。
 *
 * <p>{@code balance} 与 {@code frozen} 是**由 {@link AcctLedger} 分录推导出的快照**，
 * 不是真相源 —— 真相源永远是只增的分录流水，余额对不上时以重算分录为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("acct_account")
public class AcctAccount extends BaseEntity {

    private String accountNo;

    /** PLATFORM / VENUE / AGENT / USER。 */
    private String ownerType;

    private String ownerNo;

    private BigDecimal balance;

    /** 冻结额（提现在途、押金预授权占用）。 */
    private BigDecimal frozen;

    private String currency;
}
