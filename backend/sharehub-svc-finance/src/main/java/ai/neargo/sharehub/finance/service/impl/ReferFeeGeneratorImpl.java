package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.api.platform.dto.SiteAgentBrief;
import ai.neargo.sharehub.api.platform.event.ContractSignedEvent;
import ai.neargo.sharehub.api.platform.port.SiteAgentQueryPort;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.ShareMode;
import ai.neargo.sharehub.finance.ShareRecordStatus;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.service.ReferFeeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同签约 → 一次性牵线费。
 *
 * <h2>几条不显然的</h2>
 *
 * <ul>
 *   <li><b>{@code order_no} 存的是合同号</b>：这一笔的来源单据就是合同本身，不是某一张订单。
 *       硬塞一个订单号进去，对账时会指向一笔与它无关的交易。
 *       幂等键 {@code (order_no, dimension, payee_no, basis)} 因此天然生效：
 *       同一份合同重复投递撞键即跳过，**不会重复付**。</li>
 *   <li><b>金额直接取责任行上的 {@code one_off_amount}，不乘任何基数</b>：
 *       牵线费不随 GMV 走。用比例表达一次性费用就得编一个基数出来，
 *       日后没人说得清这笔钱怎么来的。</li>
 *   <li><b>没配金额只告警不落库</b>：没配 ≠ 0。记一条 0 元明细会把「该有人去配」
 *       和「明确不付」混为一谈 —— 同 {@link ShareGeneratorImpl} 对比例的处理。</li>
 *   <li><b>{@code rate} 写 0 而不是 null</b>：这一笔本来就没有比例。
 *       写 null 会让「比例是快照」那条口径出现一个说不清的空值，
 *       而 0 明确表示「与比例无关」，配合 basis=REFER 读得出来。</li>
 * </ul>
 */
@Service
public class ReferFeeGeneratorImpl implements ReferFeeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReferFeeGeneratorImpl.class);

    private static final String REFER = "REFER";
    private static final String AGENT = "AGENT";

    private final ShareRecordMapper records;
    private final SiteAgentQueryPort siteAgents;

    public ReferFeeGeneratorImpl(ShareRecordMapper records, SiteAgentQueryPort siteAgents) {
        this.records = records;
        this.siteAgents = siteAgents;
    }

    @Override
    public int generate(ContractSignedEvent e) {
        if (e.siteNo() == null || e.siteNo().isBlank()) {
            // 合同不绑站点时牵线责任无从查起。写入口已经拦了，这里是纵深防御：
            // 种子和历史数据里仍可能有不绑站点的老合同。
            log.warn("合同 {} 没有绑定站点，跳过牵线费结算", e.contractNo());
            return 0;
        }
        String onDate = LocalDate.now().toString();
        int n = 0;
        for (SiteAgentBrief r : siteAgents.agentsOf(e.siteNo(), onDate)) {
            if (!REFER.equalsIgnoreCase(r.role())) continue;
            n += write(e, r);
        }
        return n;
    }

    private int write(ContractSignedEvent e, SiteAgentBrief r) {
        BigDecimal amount = r.oneOffAmount();
        if (amount == null || amount.signum() <= 0) {
            log.warn("合同 {} 的牵线人 {}（站点 {}）没有配一次性对价，未生成牵线费",
                    e.contractNo(), r.agentNo(), e.siteNo());
            return 0;
        }
        ShareRecord rec = new ShareRecord();
        rec.setRecordNo(FinNos.nextNo(records, "record_no", BizKey.SHARE_RECORD, 6));
        rec.setOrderNo(e.contractNo());          // 来源单据就是合同
        rec.setDimension(AGENT);
        rec.setBasis(REFER);
        rec.setPayeeType(AGENT);
        rec.setPayeeNo(r.agentNo());
        rec.setPayeeName(r.agentName());
        rec.setRate(BigDecimal.ZERO);            // 与比例无关，见类注释
        rec.setGrossAmount(amount);              // 一次性对价没有「基数」，基数就是它自己
        rec.setAmount(amount.setScale(2, java.math.RoundingMode.DOWN));
        rec.setCurrency(e.currency());
        rec.setMode(ShareMode.LEDGER.name());
        rec.setStatus(ShareRecordStatus.PENDING.name());
        rec.setPeriod(LocalDate.now().toString().substring(0, 7));
        rec.setSourceNo(e.contractNo());
        try {
            records.insert(rec);
            return 1;
        } catch (DuplicateKeyException dup) {
            // 同一份合同重复保存/事件重投是**正常路径** —— 唯一键正是为它准备的。
            log.debug("合同 {} 的牵线费已结过（伙伴 {}），跳过", e.contractNo(), r.agentNo());
            return 0;
        }
    }
}
