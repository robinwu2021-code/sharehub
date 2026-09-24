package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.dto.FinDtos;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.entity.ShareRule;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import ai.neargo.sharehub.finance.mapper.ShareRuleMapper;
import ai.neargo.sharehub.finance.service.ShareService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分润实现。规则/明细是常规查询；重点在 {@link #summaries} 的读模型聚合与排序白名单。
 *
 * <p>实体 {@link ShareRule} 与出参 {@code FinDtos.ShareRule} 同名，故本文件里 VO 一律
 * 带 {@code FinDtos.} 前缀引用，实体裸名引用 —— 比互相起别名可读。
 */
@Service
public class ShareServiceImpl implements ShareService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * <b>受控排序白名单</b>（[api/README §1]：「后端必须按白名单校验，不得拼接任意列」）。
     * 键是前端传的 {@code sortKey}，值是聚合查询里的列别名 —— <b>只有这四个键能变成 SQL</b>，
     * 其余一律抛异常。这是本接口唯一会把外部输入放进 SQL 文本的位置，所以白名单必须是 Map 查表，
     * 不能是「校验一下再原样拼」。
     */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "shareAmount", "share_amount",
            "pendingAmount", "pending_amount",
            "gmv", "gmv",
            "orderCount", "order_count");

    private static final Set<String> DIR_WHITELIST = Set.of("asc", "desc");

    private final ShareRecordMapper recordMapper;
    private final ShareRuleMapper ruleMapper;

    public ShareServiceImpl(ShareRecordMapper recordMapper, ShareRuleMapper ruleMapper) {
        this.recordMapper = recordMapper;
        this.ruleMapper = ruleMapper;
    }

    // ——————————————————————— 分润明细 ———————————————————————

    @Override
    public PageResult<FinDtos.ShareRecord> pageRecords(Integer page, Integer size, String keyword,
                                                       String dimension, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<ShareRecord> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(ShareRecord::getRecordNo, kw)
                    .or().like(ShareRecord::getOrderNo, kw)
                    .or().like(ShareRecord::getPayeeName, kw));
        }
        if (dimension != null && !dimension.isBlank()) w.eq(ShareRecord::getDimension, dimension);
        if (status != null && !status.isBlank()) w.eq(ShareRecord::getStatus, status);
        w.orderByDesc(ShareRecord::getId);

        Page<ShareRecord> r = recordMapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(ShareServiceImpl::toRecordVO).toList(), r.getTotal());
    }

    // ——————————————————————— 分润统计（读模型） ———————————————————————

    @Override
    public PageResult<FinDtos.ShareSummary> summaries(Integer page, Integer size, String dimension,
                                                      String period, String sortKey, String sortDir) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        QueryWrapper<ShareRecord> w = new QueryWrapper<>();
        w.select("dimension",
                "payee_no",
                "MAX(payee_name) AS payee_name",
                "MAX(currency) AS currency",
                "period",
                "COUNT(*) AS order_count",
                // GMV 取**快照列**（V34）而非 amount/rate 反推：固定额分润 rate=0、阶梯分润
                // 有效费率≠单一 rate，反推在这两种模式下必错（前者整行消失、后者偏差）
                "COALESCE(SUM(gross_amount), 0) AS gmv",
                "COALESCE(SUM(amount), 0) AS share_amount",
                "COALESCE(SUM(CASE WHEN status = 'DONE' THEN amount ELSE 0 END), 0) AS settled_amount",
                "COALESCE(SUM(CASE WHEN status <> 'DONE' THEN amount ELSE 0 END), 0) AS pending_amount");

        if (dimension != null && !dimension.isBlank()) w.eq("dimension", dimension);
        // 账期按**列**过滤（V34）：此前是 DATE_FORMAT 现推，函数包列走不了索引，
        // 且「创建月」≠「归属月」（跨月补记的分润会记错账期）
        if (period != null && !period.isBlank()) w.eq("period", period);
        w.groupBy("dimension", "payee_no", "period");

        if (sortKey != null && !sortKey.isBlank()) {
            String col = SORT_WHITELIST.get(sortKey);
            if (col == null) {
                // 白名单外一律拒绝，绝不「尽力而为地放行」——这里放行一次就是一个 SQL 注入面
                throw new IllegalArgumentException("不支持的排序列: " + sortKey
                        + "（仅允许 " + String.join("|", SORT_WHITELIST.keySet()) + "）");
            }
            String dir = (sortDir == null || sortDir.isBlank()) ? "desc" : sortDir.toLowerCase();
            if (!DIR_WHITELIST.contains(dir)) {
                throw new IllegalArgumentException("不支持的排序方向: " + sortDir + "（仅允许 asc|desc）");
            }
            w.orderBy(true, "asc".equals(dir), col);
        } else {
            w.orderByDesc("share_amount");
        }

        // GROUP BY 查询的 total 不能交给分页插件的 count（它会算出分组前的行数），
        // 故一次取全量再内存分页；财务统计的分组基数是「主体数 × 月份数」，量级可控。
        List<Map<String, Object>> rows = recordMapper.selectMaps(w);
        long total = rows.size();
        int from = Math.min((p - 1) * s, rows.size());
        int to = Math.min(from + s, rows.size());
        List<FinDtos.ShareSummary> list = rows.subList(from, to).stream()
                .map(ShareServiceImpl::toSummary).toList();
        return new PageResult<>(list, total);
    }

    // ——————————————————————— 分润规则 ———————————————————————

    @Override
    public FinDtos.ShareRule saveRule(ShareRule body) {
        if (body == null) throw new IllegalArgumentException("分润规则不能为空");
        // 取价按 payee_no **精确匹配**（ShareGeneratorImpl.ruleOf）。没有编号的规则
        // 一条都命中不了 —— 界面上看着配好了，分账时那个分成方却拿不到钱，而且不报错。
        // 这是「按名字连」那类错误里最贵的一种：错的不是显示，是钱。
        if (body.getPayeeNo() == null || body.getPayeeNo().isBlank()) {
            throw new IllegalArgumentException("分成方编号必填：取价按编号匹配，只有名字的规则永远命中不了");
        }
        // 不在这里校验「这个编号真的存在」：场地方/代理商主数据在 platform，
        // 为一次校验新开一条跨模块查询接口不划算。运营端的下拉已经只给真实主数据，
        // 且 mock 层做了存在性校验（绕过 UI 直调同样被拒）。
        if (body.getRate() != null
                && (body.getRate().signum() < 0 || body.getRate().compareTo(BigDecimal.ONE) > 0)) {
            // [db-design §1.5]：*_rate 是 0..1 的小数，不是百分数。传 30 表示 30% 是最常见的踩坑
            throw new IllegalArgumentException("分成比率 rate 必须是 0..1 的小数（不是百分数）: " + body.getRate());
        }

        String no = body.getRuleNo();
        if (no == null || no.isBlank()) {
            body.setRuleNo(FinNos.nextNo(ruleMapper, "rule_no", BizKey.SHARE_RULE, 4));
            body.setTenantId(SecurityUtils.tenantId());
            ruleMapper.insert(body);
        } else {
            ShareRule current = byRuleNo(no);
            if (current == null) {
                if (body.getTenantId() == null) body.setTenantId(SecurityUtils.tenantId());
                ruleMapper.insert(body);
            } else {
                body.setId(current.getId());
                body.setVersion(current.getVersion());
                if (body.getTenantId() == null) body.setTenantId(current.getTenantId());
                /*
                 * 改前改后进审计（T3-2）。分润规则是第一个接入的，因为它最需要 ——
                 * 结算争议时要问的是「费率是从 8% 改成 5% 的，还是一直就是 5%」，
                 * 而只记一条「POST /api/trade/share-rules」答不出来：
                 * 去库里查当前值，查到的正是被改过之后的那个。
                 *
                 * 字段是**白名单**（见 AuditChanges 类注释）：这六个会影响分多少钱给谁，
                 * 其余（时间戳、版本号、租户）不进审计。
                 * 标签写运营在界面上看到的名字，不是列名 —— 详情页直接显示这个字符串。
                 */
                AuditChanges.record("分润比率", current.getRate(), body.getRate());
                AuditChanges.record("分账方式", current.getMode(), body.getMode());
                AuditChanges.record("分润依据", current.getBasis(), body.getBasis());
                AuditChanges.record("优先级", current.getPriority(), body.getPriority());
                AuditChanges.record("分成方编号", current.getPayeeNo(), body.getPayeeNo());
                AuditChanges.record("分成方名称", current.getPayeeName(), body.getPayeeName());
                ruleMapper.updateById(body);
            }
        }
        return toRuleVO(byRuleNo(body.getRuleNo()));
    }

    @Override
    public PageResult<FinDtos.ShareRule> pageRules(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<ShareRule> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(ShareRule::getRuleNo, keyword)
                    .or().like(ShareRule::getPayeeNo, keyword)
                    .or().like(ShareRule::getPayeeName, keyword));
        }
        w.orderByAsc(ShareRule::getPriority).orderByDesc(ShareRule::getId);
        Page<ShareRule> r = ruleMapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(ShareServiceImpl::toRuleVO).toList(), r.getTotal());
    }

    private ShareRule byRuleNo(String ruleNo) {
        return ruleMapper.selectOne(new LambdaQueryWrapper<ShareRule>()
                .eq(ShareRule::getRuleNo, ruleNo).last("limit 1"));
    }

    // ——————————————————————— 映射 ———————————————————————

    private static FinDtos.ShareRule toRuleVO(ShareRule e) {
        if (e == null) return null;
        return new FinDtos.ShareRule(e.getRuleNo(), e.getDimension(), e.getPayeeNo(), e.getPayeeName(),
                e.getBasis(), e.getMode(), e.getRate(), e.getPriority(), e.getFormula(), e.getCurrency());
    }

    private static FinDtos.ShareRecord toRecordVO(ShareRecord e) {
        return new FinDtos.ShareRecord(e.getRecordNo(), e.getOrderNo(), e.getDimension(), e.getPayeeNo(),
                e.getPayeeName(), e.getBasis(), e.getAmount(), e.getRate(), e.getCurrency(), e.getMode(),
                e.getStatus(), e.getSettleNo(), fmt(e.getCreatedAt()),
                e.getPeriod(), e.getGrossAmount());
    }

    private static FinDtos.ShareSummary toSummary(Map<String, Object> m) {
        BigDecimal share = dec(m.get("share_amount"));
        BigDecimal settled = dec(m.get("settled_amount"));
        return new FinDtos.ShareSummary(
                str(m.get("dimension")), str(m.get("payee_no")), str(m.get("payee_name")), str(m.get("period")),
                m.get("order_count") == null ? 0L : ((Number) m.get("order_count")).longValue(),
                dec(m.get("gmv")).setScale(2, java.math.RoundingMode.HALF_UP),
                share,
                settled,
                // 待结算是派生值：分润额 − 已结算。SQL 里也算了一份，两处口径必须一致，
                // 以这里为准（SQL 那份按 status 分桶，遇到未来新增状态时以减法为准更稳）
                share.subtract(settled),
                str(m.get("currency")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(o));
    }

    static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }
}
