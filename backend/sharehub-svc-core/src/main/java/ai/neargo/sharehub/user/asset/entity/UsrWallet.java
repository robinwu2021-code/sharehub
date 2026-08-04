package ai.neargo.sharehub.user.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户钱包（usr_wallet，[db-design §6.2]）。1 用户 1 钱包（UK c_user_no）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：DDL 有 {@code version} 但没有 {@code deleted}/{@code created_at}
 * （钱包不允许删）。
 *
 * <p><b>用户价值画像不落列</b>（[db-design §1.4 / api §5.1]）：
 * {@code orderCount}/{@code orderAmount}/{@code rechargeCount}/{@code rechargeAmount}
 * 由 {@code ord_order} + {@code usr_recharge_order} 按 {@code c_user_no} 聚合得到，
 * <b>刻意不作为实体字段</b> —— 落冗余列必然与订单页/充值订单页不自洽（前端 mock 已踩过）。
 * 它们只出现在出参 VO 里。
 */
@Data
@TableName("usr_wallet")
public class UsrWallet {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务键，前缀 {@code U}（[db-design §1.4.1]：U 覆盖 C端用户/会员/钱包/白名单）。 */
    private String walletNo;

    private String tenantId;

    private String cUserNo;

    /** 可用余额（本金）。 */
    private BigDecimal balance;

    /** 赠金余额（充值套餐赠送，前端 VO 里叫 {@code bonus}）。 */
    private BigDecimal giftBalance;

    /** 押金余额。 */
    private BigDecimal depositAmount;

    /** 冻结金额（进行中订单预授权/待结算）。 */
    private BigDecimal frozenAmount;

    private String currency;

    private String updatedAt;

    @Version
    private Long version;
}
