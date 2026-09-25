package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.finance.ShareMode;
import ai.neargo.sharehub.finance.ShareRecordStatus;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.api.platform.dto.SiteAgentBrief;
import ai.neargo.sharehub.api.platform.dto.SiteSharingBrief;
import ai.neargo.sharehub.api.platform.port.SiteAgentQueryPort;
import ai.neargo.sharehub.api.platform.port.SiteSharingQueryPort;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.ShareRuleMapper;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 订单 → 分润明细。**这是 MVP 三条硬阻塞里唯一没有外部依赖的一条**，补上之后
 * 「订单产生收入」与「谁该分到钱」之间才第一次有代码连着。
 *
 * <h2>费率取自哪里（运营管理清单 D2 的落地取舍）</h2>
 *
 * <ul>
 *   <li><b>VENUE 以进场合同为准</b>：合同是**按站点**签的、是双方签过字的那份；
 *       `share_rule` 按分成方配比例、没有站点维度，而同一个场地方在不同商场的分成
 *       完全可以不一样。没有生效合同时回落到 {@code share_rule(VENUE, venueNo)}，
 *       并在日志里说明用的是回落值。</li>
 *   <li><b>AGENT 按站点上的责任逐条分</b>（V53 / ADR-027）：读 {@code loc_site_agent}，
 *       出资 / 拓展 / 运维各一条，各按 {@code share_rule(AGENT, agentNo, basis)} 取比例。
 *       站点没配责任行时<b>回落</b>到旧口径（一条，依据记 OPERATE）——
 *       没配不能等于不分账，那是静默少付合作伙伴。</li>
 * </ul>
 *
 * <h2>几条不显然的</h2>
 *
 * <ul>
 *   <li><b>基数是实收不是应收</b>：免单与券抵扣的部分没有真实现金流，
 *       按应收分账等于用平台的钱替用户给场地方付分成。基数由发布方带来。</li>
 *   <li><b>比例写快照</b>：规则或合同改了不重算历史分润 —— 这是财务口径，不是缓存。</li>
 *   <li><b>金额向下取整到分</b>：分账合计不得超过基数。四舍五入会让三方之和
 *       偶尔比收到的钱多一分，对账时这一分要查很久。</li>
 *   <li><b>幂等靠唯一键</b>（V53 {@code uk_srec_order_payee_basis}，V37 那条的细化）：
 *       outbox 重投是设计内的，撞键即跳过，不报错也不重复记账。
 *       键里含 {@code basis}，所以同一代理在一单里可以有出资 + 运维两条，而重投仍然拦得住。</li>
 *   <li><b>REFER 不在逐单分润里</b>：牵线的对价是一次性介绍费，签约时付（A2-4）。
 *       按逐单比例付会变成「介绍一次、分十年」。</li>
 * </ul>
 */
@Service
public class ShareGeneratorImpl implements ShareGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShareGeneratorImpl.class);

    /** 逐单分润里**不生成**的责任：牵线的对价是一次性介绍费，签约时付（A2-4），不按单分。 */
    private static final String REFER = "REFER";

    /** 没配责任行时的回落依据 —— 今天那一条代理分成的实际含义就是运维分成。 */
    private static final String FALLBACK_BASIS = "OPERATE";

    /** VENUE 维度没有责任细分，依据就是维度本身。空串而非 null：幂等键含此列。 */
    private static final String NO_BASIS = "";

    private final ShareRecordMapper records;
    private final ShareRuleMapper rules;
    private final SiteSharingQueryPort siteSharing;
    private final SiteAgentQueryPort siteAgents;
    private final ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agentDirectory;

    public ShareGeneratorImpl(ShareRecordMapper records, ShareRuleMapper rules,
                              SiteSharingQueryPort siteSharing, SiteAgentQueryPort siteAgents,
                              ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agentDirectory) {
        this.agentDirectory = agentDirectory;
        this.records = records;
        this.rules = rules;
        this.siteSharing = siteSharing;
        this.siteAgents = siteAgents;
    }

    @Override
    public int generate(OrderSettledEvent e) {
        BigDecimal gross = e.grossAmount();
        if (gross == null || gross.signum() <= 0) {
            // 免单 / 全额券：没有真实收入，不该产生任何分成。**显式返回而不是算出 0 元记一笔** ——
            // 0 元明细会让结算单里出现一堆无意义的行，运营以为是漏算。
            return 0;
        }
        if (e.siteNo() == null || e.siteNo().isBlank()) {
            // 归属链断了（机柜没绑点位/站点）。这不是「没有分成」，是**数据缺陷**：
            // 这笔钱本该分给谁，现在没人知道。必须留痕，否则它会悄悄进平台口袋。
            log.warn("订单 {} 无站点归属（机柜 {}），跳过分润生成 —— 请检查机柜的点位/站点绑定",
                    e.orderNo(), e.cabinetNo());
            return 0;
        }

        String onDate = e.period() == null ? null : e.period() + "-01";
        SiteSharingBrief site = siteSharing.sharingOf(e.siteNo(), onDate);
        int n = 0;

        // ── 场地方 ──
        if (site != null && site.venueNo() != null && !site.venueNo().isBlank()) {
            BigDecimal rate = site.venueRate();
            String source = site.contractNo();
            if (rate == null) {
                ShareRule r = ruleOf("VENUE", site.venueNo(), NO_BASIS);
                rate = r == null ? null : r.getRate();
                source = r == null ? null : r.getRuleNo();
                if (rate != null) {
                    log.info("站点 {} 没有生效合同，场地方分成回落到规则 {}（{}）",
                            e.siteNo(), source, rate);
                }
            }
            n += write(e, "VENUE", NO_BASIS, site.venueNo(), site.venueName(), rate, source, gross,
                    site.currency());
        }

        // ── 伙伴（按责任逐条）──
        n += agentShares(e, onDate, gross);
        return n;
    }

    /**
     * 站点上每一项责任各分一条（ADR-027 §四）。
     *
     * <p><b>为什么不是一个代理一条</b>：一个站点上「谁出的钱、谁找来的、谁在维护」常常
     * 不是同一个人。压成一条的代价不是不精确，是<b>不可追溯</b> ——
     * 结算争议时说不清这 8% 里几个点是运维、几个点是出资。
     *
     * <p><b>REFER 跳过</b>：牵线的对价是「把关系介绍过来」这个一次性动作，
     * 按逐单比例付会变成「介绍一次、分十年」。它走签约事件（A2-4），不在这里。
     */
    private int agentShares(OrderSettledEvent e, String onDate, BigDecimal gross) {
        List<SiteAgentBrief> roles = siteAgents.agentsOf(e.siteNo(), onDate);

        // ── 回落：站点没配责任行 ──
        // **这个分支必须留**。责任表是逐站点配的，没配的站点不能因此不分账 ——
        // 那是静默少付合作伙伴，与「静默免单」同一性质。
        if (roles.isEmpty()) {
            if (e.agentNo() == null || e.agentNo().isBlank()) return 0;  // 平台直营，本来就没有代理分成
            ShareRule r = ruleOf("AGENT", e.agentNo(), FALLBACK_BASIS);
            log.info("站点 {} 未配责任行，代理分成回落到 share_rule(AGENT, {})，依据记为 {}",
                    e.siteNo(), e.agentNo(), FALLBACK_BASIS);
            return write(e, "AGENT", FALLBACK_BASIS, e.agentNo(), r == null ? null : r.getPayeeName(),
                    opsAdjusted(e, e.agentNo(), FALLBACK_BASIS, r == null ? null : r.getRate()), r == null ? null : r.getRuleNo(), gross, null);
        }

        int n = 0;
        for (SiteAgentBrief role : roles) {
            if (REFER.equalsIgnoreCase(role.role())) continue;
            ShareRule r = role.ruleNo() == null || role.ruleNo().isBlank()
                    ? ruleOf("AGENT", role.agentNo(), role.role())
                    : ruleByNo(role.ruleNo());
            String name = role.agentName() != null ? role.agentName()
                    : (r == null ? null : r.getPayeeName());
            n += write(e, "AGENT", role.role(), role.agentNo(), name,
                    opsAdjusted(e, role.agentNo(), role.role(), r == null ? null : r.getRate()), r == null ? null : r.getRuleNo(), gross, null);
        }
        return n;
    }

    /**
     * 运维分成系数（批次 F5，裁决 #1）：OPERATE 份额乘上该代理本账期适用的系数（上月考核的结果，没有考核按 1）。
     * 不逐单扣 —— 一张单超时被接管不扣这张单的钱，月度达成率低才整体下调，写进代理协议。拓展（DEVELOP）不受影响。
     */
    private BigDecimal opsAdjusted(OrderSettledEvent e, String agentNo, String basis, BigDecimal rate) {
        if (rate == null || !FALLBACK_BASIS.equalsIgnoreCase(basis)) return rate;
        BigDecimal coef = agentDirectory.opsCoefficient(agentNo, e.period());
        if (coef == null || coef.compareTo(BigDecimal.ONE) == 0) return rate;
        BigDecimal adjusted = rate.multiply(coef).setScale(4, RoundingMode.DOWN);
        log.info("运维分成系数 orderNo={} agentNo={} period={} {} × {} = {}", e.orderNo(), agentNo, e.period(), rate, coef, adjusted);
        return adjusted;
    }

    /** 责任行指名了规则号时按号取 —— 指名就是运营的明确意图，不该再被 priority 覆盖。 */
    private ShareRule ruleByNo(String ruleNo) {
        return rules.selectOne(new LambdaQueryWrapper<ShareRule>()
                .eq(ShareRule::getRuleNo, ruleNo).last("limit 1"));
    }

    /**
     * 某分成方就某项依据当前生效的规则。
     *
     * <p>同一分成方可能配了多条，取 {@code priority} 最小的那条
     * （[db-design §5.4]「数值小者先命中」）；并列时按规则号兜底，保证补算两次结果一致。
     *
     * <p><b>先按 basis 精确找，找不到退回该分成方的通用规则</b>（basis 为空串的那条）。
     * 两段式是为了让 V53 之前配好的规则继续有效：那些规则没有 basis，
     * 若只按精确匹配找，责任配好的那天所有分账会同时归零 —— 而且不报错。
     */
    private ShareRule ruleOf(String dimension, String payeeNo, String basis) {
        ShareRule exact = pick(dimension, payeeNo, basis);
        return exact != null ? exact : pick(dimension, payeeNo, NO_BASIS);
    }

    private ShareRule pick(String dimension, String payeeNo, String basis) {
        List<ShareRule> list = rules.selectList(new LambdaQueryWrapper<ShareRule>()
                .eq(ShareRule::getDimension, dimension)
                .eq(ShareRule::getPayeeNo, payeeNo)
                .and(w -> w.eq(ShareRule::getBasis, basis)
                        .or(basis == null || basis.isEmpty(), x -> x.isNull(ShareRule::getBasis))));
        return list.stream()
                .min(Comparator.comparingInt((ShareRule r) -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority())
                        .thenComparing(r -> r.getRuleNo() == null ? "" : r.getRuleNo()))
                .orElse(null);
    }

    private int write(OrderSettledEvent e, String dimension, String basis, String payeeNo,
                      String payeeName, BigDecimal rate, String sourceNo, BigDecimal gross,
                      String currency) {
        if (rate == null || rate.signum() <= 0) {
            // 没配比例 ≠ 比例为 0。前者是配置缺失（该有人去配），后者是明确的「不分」。
            // 记一条 0 元明细会把两者混为一谈，所以这里只告警不落库。
            log.warn("订单 {} 的 {} 分成方 {}（依据 {}）没有可用比例，未生成分润明细",
                    e.orderNo(), dimension, payeeNo, basis.isEmpty() ? "-" : basis);
            return 0;
        }
        ShareRecord r = new ShareRecord();
        r.setRecordNo(FinNos.nextNo(records, "record_no", BizKey.SHARE_RECORD, 6));
        r.setOrderNo(e.orderNo());
        r.setDimension(dimension);
        r.setBasis(basis);           // 空串而非 null：幂等键含此列，NULL 之间互不相同
        r.setPayeeType(dimension);   // 冗余，结算单直接取用（见实体注释）
        r.setPayeeNo(payeeNo);
        r.setPayeeName(payeeName);
        r.setRate(rate);
        r.setGrossAmount(gross);
        // 向下取整到分：分账合计不得超过基数
        r.setAmount(gross.multiply(rate).setScale(2, RoundingMode.DOWN));
        r.setCurrency(currency == null ? e.currency() : currency);
        r.setMode(ShareMode.LEDGER.name());
        r.setStatus(ShareRecordStatus.PENDING.name());
        r.setPeriod(e.period());
        r.setSourceNo(sourceNo);
        try {
            records.insert(r);
            return 1;
        } catch (DuplicateKeyException dup) {
            // 事件重投。这是**正常路径**，不是错误 —— 唯一键正是为它准备的。
            log.debug("订单 {} 的 {} 分润（依据 {}）已存在，跳过（事件重投）",
                    e.orderNo(), dimension, basis.isEmpty() ? "-" : basis);
            return 0;
        }
    }
}
