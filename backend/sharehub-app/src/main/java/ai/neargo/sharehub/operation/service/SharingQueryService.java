package ai.neargo.sharehub.operation.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.dto.SiteSharingBrief;
import ai.neargo.sharehub.api.platform.port.SiteSharingQueryPort;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.ShareRuleMapper;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.sharehub.operation.dto.OperationDtos.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * 分成的两个视角：按站点看（OM-S5）、按分成方看（OM-S6）。
 *
 * <p><b>它们是同一份数据的两个方向，不是两套配置</b> —— 竞品把「场地方分成」「代理商分成」
 * 做成两个菜单两张表，于是「这个站点到底分出去多少」没有人能一次答上来。
 *
 * <p><b>费率口径与 {@code ShareGeneratorImpl} 完全一致</b>（运营管理清单 D2）：
 * 场地方以**进场合同**为准（合同按站点签、签过字的那份），没有生效合同才回落到
 * {@code share_rule(VENUE, venueNo)}；代理只看 {@code share_rule(AGENT, agentNo)}。
 * 两处口径一旦分叉，这一页显示的比例就和真实分账的比例对不上 —— 而运营正是看这一页去核对的。
 */
@Service
public class SharingQueryService {

    private final SiteMapper sites;
    private final ShareRuleMapper rules;
    private final ShareRecordMapper records;
    private final SiteSharingQueryPort siteSharing;

    public SharingQueryService(SiteMapper sites, ShareRuleMapper rules, ShareRecordMapper records,
                               SiteSharingQueryPort siteSharing) {
        this.sites = sites;
        this.rules = rules;
        this.records = records;
        this.siteSharing = siteSharing;
    }

    public PageResult<SiteSharingRow> pageSites(Integer page, Integer size, String keyword, String state) {
        List<SiteSharingRow> all = rows().stream()
                .filter(r -> state == null || state.isBlank() || state.equals(r.state()))
                .filter(r -> keyword == null || keyword.isBlank()
                        || contains(r.siteName(), keyword) || contains(r.siteNo(), keyword)
                        || contains(r.venueName(), keyword))
                .sorted(Comparator.<SiteSharingRow>comparingInt(r -> order(r.state()))
                        .thenComparing(SiteSharingRow::siteName, Comparator.nullsLast(String::compareTo)))
                .toList();
        return paged(all, page, size);
    }

    public SharingStats stats() {
        List<SiteSharingRow> all = rows();
        return new SharingStats(all.size(),
                (int) all.stream().filter(r -> "OK".equals(r.state())).count(),
                (int) all.stream().filter(r -> "MISSING".equals(r.state())).count(),
                (int) all.stream().filter(r -> "INVALID".equals(r.state())).count());
    }

    public PageResult<PayeeSharingRow> pagePayees(Integer page, Integer size, String keyword, String payeeType) {
        // 近 30 日分成金额：按分成方汇总真实分润明细
        Map<String, BigDecimal> amount = new HashMap<>();
        String since = LocalDate.now().minusDays(30).toString();
        for (ShareRecord r : records.selectList(new LambdaQueryWrapper<ShareRecord>()
                .ge(ShareRecord::getCreatedAt, since))) {
            if (r.getPayeeNo() == null) continue;
            amount.merge(r.getPayeeType() + ":" + r.getPayeeNo(),
                    r.getAmount() == null ? BigDecimal.ZERO : r.getAmount(), BigDecimal::add);
        }

        Map<String, List<PayeeSiteRef>> byPayee = new LinkedHashMap<>();
        Map<String, String> typeOf = new HashMap<>();
        Map<String, String> nameOf = new HashMap<>();
        for (SiteSharingRow row : rows()) {
            for (SitePayee p : row.payees()) {
                String key = p.payeeType() + ":" + p.payeeNo();
                typeOf.put(key, p.payeeType());
                nameOf.put(key, p.payeeName() == null ? p.payeeNo() : p.payeeName());
                byPayee.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new PayeeSiteRef(row.siteNo(), row.siteName(), p.rate()));
            }
        }

        List<PayeeSharingRow> all = byPayee.entrySet().stream()
                .filter(e -> payeeType == null || payeeType.isBlank() || payeeType.equals(typeOf.get(e.getKey())))
                .filter(e -> keyword == null || keyword.isBlank() || contains(nameOf.get(e.getKey()), keyword))
                .map(e -> {
                    List<PayeeSiteRef> ss = e.getValue().stream()
                            .sorted(Comparator.comparing(PayeeSiteRef::rate).reversed()).toList();
                    BigDecimal min = ss.stream().map(PayeeSiteRef::rate).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                    BigDecimal max = ss.stream().map(PayeeSiteRef::rate).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                    return new PayeeSharingRow(typeOf.get(e.getKey()), nameOf.get(e.getKey()), ss.size(),
                            min, max, amount.getOrDefault(e.getKey(), BigDecimal.ZERO), "AED", ss);
                })
                .sorted(Comparator.comparing(PayeeSharingRow::siteCount).reversed())
                .toList();
        return paged(all, page, size);
    }

    // ——————————————————————— 内部 ———————————————————————

    /** 逐站算出分成方与配置状态。站点是百级规模，逐站一次 Port 调用可接受。 */
    private List<SiteSharingRow> rows() {
        String today = LocalDate.now().toString();
        List<SiteSharingRow> out = new ArrayList<>();
        for (LocSite s : sites.selectList(new LambdaQueryWrapper<LocSite>().isNull(LocSite::getArchivedAt))) {
            SiteSharingBrief brief = siteSharing.sharingOf(s.getSiteNo(), today);
            List<SitePayee> payees = new ArrayList<>();

            if (brief != null && brief.venueNo() != null && !brief.venueNo().isBlank()) {
                if (brief.venueRate() != null) {
                    payees.add(new SitePayee("VENUE", brief.venueNo(), brief.venueName(),
                            brief.venueRate(), "LEDGER", "CONTRACT", brief.contractNo()));
                } else {
                    ShareRule r = ruleOf("VENUE", brief.venueNo());
                    if (r != null && r.getRate() != null) {
                        payees.add(new SitePayee("VENUE", brief.venueNo(),
                                r.getPayeeName() == null ? brief.venueName() : r.getPayeeName(),
                                r.getRate(), r.getMode(), "RULE", r.getRuleNo()));
                    }
                }
            }
            if (s.getAgentNo() != null && !s.getAgentNo().isBlank()) {
                ShareRule r = ruleOf("AGENT", s.getAgentNo());
                if (r != null && r.getRate() != null) {
                    payees.add(new SitePayee("AGENT", s.getAgentNo(),
                            r.getPayeeName() == null ? s.getAgentNo() : r.getPayeeName(),
                            r.getRate(), r.getMode(), "RULE", r.getRuleNo()));
                }
            }

            BigDecimal total = payees.stream().map(SitePayee::rate)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
            BigDecimal platform = BigDecimal.ONE.subtract(total).setScale(4, RoundingMode.HALF_UP);

            String state = "OK";
            String detail = "";
            if (payees.isEmpty()) {
                state = "MISSING";
                detail = "没有任何分成方，这个站点的收入全部留在平台";
            } else if (total.compareTo(BigDecimal.ONE) > 0) {
                state = "INVALID";
                detail = "各方比例合计 " + pct(total) + "，超过 100%";
            } else if (brief != null && brief.contractNo() == null && "ACTIVE".equals(s.getStatus())
                    && payees.stream().anyMatch(p -> "VENUE".equals(p.payeeType()))) {
                // 有场地方分成、却没有生效合同：比例来自规则的回落值，随时可能与实际约定不符
                state = "INVALID";
                detail = "没有生效中的进场合同，场地方比例取自分润规则的回落值";
            }
            out.add(new SiteSharingRow(s.getSiteNo(), s.getName(),
                    brief == null ? s.getVenueName() : brief.venueName(),
                    payees, total, platform, null, state, detail));
        }
        return out;
    }

    private ShareRule ruleOf(String dimension, String payeeNo) {
        return rules.selectList(new LambdaQueryWrapper<ShareRule>()
                        .eq(ShareRule::getDimension, dimension)
                        .eq(ShareRule::getPayeeNo, payeeNo)).stream()
                .min(Comparator.comparingInt((ShareRule r) -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority())
                        .thenComparing(r -> r.getRuleNo() == null ? "" : r.getRuleNo()))
                .orElse(null);
    }

    /** 异常排最前、缺配置次之 —— 这一页是拿来处理问题的，正常的排后面。 */
    private static int order(String state) {
        return "INVALID".equals(state) ? 0 : "MISSING".equals(state) ? 1 : 2;
    }

    private static String pct(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase().contains(kw.toLowerCase());
    }

    private static <T> PageResult<T> paged(List<T> all, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        int from = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(from, Math.min(from + s, all.size())), (long) all.size());
    }
}
