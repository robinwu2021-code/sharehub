package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.platform.sys.dto.SysDtos.BizRules;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.WithdrawRule;

/**
 * 业务规则（三分区单例）。**不走通用 CRUD** —— 它没有列表、没有业务键，
 * 读是「三行拼成一个对象」，写是「分区合并」，与字典表的形态完全不同。
 */
public interface BizRuleService {

    /** 读全量三分区；缺失的分区给出内置默认值，页面永远不会拿到 null 分区。 */
    BizRules get();

    /**
     * **分区保存（Partial 语义）**：只合并 {@code partial} 里非空的分区，
     * 其余分区**原样保留**。页面三个保存按钮 → 三次独立 POST，互不覆盖。
     */
    BizRules save(BizRules partial);

    /**
     * 提现规则直读 —— 财务侧（提现申请/手续费计算）**必须**经此取费率与封顶，
     * 不得在自己域内另存一份阈值。口径分叉 = 对账永远差钱。
     */
    WithdrawRule withdrawRule();
}
