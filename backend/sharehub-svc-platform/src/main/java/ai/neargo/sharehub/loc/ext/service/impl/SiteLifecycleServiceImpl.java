package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.SiteLifecycle;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.StageChangeReq;
import ai.neargo.sharehub.loc.ext.entity.LocSiteLifecycle;
import ai.neargo.sharehub.loc.ext.entity.LocSiteLifecycleLog;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteLifecycleLogMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteLifecycleMapper;
import ai.neargo.sharehub.loc.ext.service.SiteLifecycleService;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 门店生命周期实现。
 *
 * <p>{@link SiteMapper} 只用于**读**站点展示名（{@code site_name} 不是本表的列，
 * [db-design §3.4] 未列入），不做任何写入 —— {@code loc} 主包的文件本分片不改动。
 */
@Service
public class SiteLifecycleServiceImpl implements SiteLifecycleService {

    /** [db-design §3.4] 的阶段取值域。**故意不定义单向状态机** —— 见 {@link #changeStage}。 */
    private static final Set<String> STAGES =
            Set.of("PROSPECTING", "SIGNED", "LIVE", "ACTIVE", "CHURNED", "CLOSED");

    private static final String TENANT_MAIN = "MAIN";

    private final LocSiteLifecycleMapper mapper;
    private final LocSiteLifecycleLogMapper logMapper;
    private final SiteMapper siteMapper;

    public SiteLifecycleServiceImpl(LocSiteLifecycleMapper mapper,
                                    LocSiteLifecycleLogMapper logMapper,
                                    SiteMapper siteMapper) {
        this.mapper = mapper;
        this.logMapper = logMapper;
        this.siteMapper = siteMapper;
    }

    @Override
    public PageResult<SiteLifecycle> page(Integer page, Integer size, String keyword, String stage) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<LocSiteLifecycle> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(LocSiteLifecycle::getSiteNo, keyword)
                    .or().like(LocSiteLifecycle::getOwner, keyword));
        }
        if (stage != null && !stage.isBlank()) w.eq(LocSiteLifecycle::getStage, stage);
        w.orderByDesc(LocSiteLifecycle::getId);

        Page<LocSiteLifecycle> r = mapper.selectPage(new Page<>(p, s), w);
        Map<String, String> names = siteNames(r.getRecords().stream().map(LocSiteLifecycle::getSiteNo).toList());
        List<SiteLifecycle> rows = r.getRecords().stream().map(e -> toVO(e, names.get(e.getSiteNo()))).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public SiteLifecycle get(String siteNo) {
        LocSiteLifecycle e = byNo(siteNo);
        return e == null ? null : toVO(e, siteNames(List.of(siteNo)).get(siteNo));
    }

    /**
     * 阶段流转。
     *
     * <p><b>为什么不上状态机</b>：[db-design §9A] 只给了 {@code ord_order}/{@code wo_order}/
     * {@code dev_*} 三处状态机定稿，门店生命周期不在其列；现实里「CHURNED 的店重新签回来」
     * 是正常业务，硬编一条单向链会立刻挡住合法操作。因此只校验**取值合法**与**非空转**，
     * 真正的约束交给留痕：谁在什么时候把它从哪个阶段挪到哪个阶段，log 表一行不落。
     */
    @Override
    @Transactional
    public SiteLifecycle changeStage(String siteNo, StageChangeReq req) {
        if (siteNo == null || siteNo.isBlank()) throw new IllegalArgumentException("siteNo 必填");
        if (req == null || req.stage() == null || req.stage().isBlank()) {
            throw new IllegalArgumentException("目标阶段 stage 必填");
        }
        String to = req.stage();
        if (!STAGES.contains(to)) throw new IllegalArgumentException("门店生命周期阶段非法: " + to);

        LocSiteLifecycle e = byNo(siteNo);
        String from = (e == null) ? null : e.getStage();   // 首次建档 fromStage 记空
        if (to.equals(from)) {
            throw new IllegalArgumentException("目标阶段与当前阶段相同，无需流转: " + to);
        }

        boolean insert = (e == null);
        if (insert) {
            e = new LocSiteLifecycle();
            e.setSiteNo(siteNo);
            e.setTenantId(TENANT_MAIN);
            e.setCurrency("AED");
            e.setGmvLtm(BigDecimal.ZERO);
        }
        e.setStage(to);
        e.setStageAt(LocalDate.now().toString());          // DDL 为 DATE，仅日期语义
        if (req.operator() != null && !req.operator().isBlank()) e.setOwner(req.operator());
        // gmv_ltm 是**阶段决策快照**：只在流转时写入，留空则沿用上一次的值，**绝不定时回刷**
        // （[db-design §3.4] 注）。要实时值请查站点坪效 GET /api/ops/site-analysis。
        if (req.gmvLtm() != null) e.setGmvLtm(req.gmvLtm());
        if (req.currency() != null && !req.currency().isBlank()) e.setCurrency(req.currency());

        if (insert) mapper.insert(e); else mapper.updateById(e);

        LocSiteLifecycleLog log = new LocSiteLifecycleLog();
        log.setTenantId(e.getTenantId() == null ? TENANT_MAIN : e.getTenantId());
        log.setSiteNo(siteNo);
        log.setFromStage(from);
        log.setToStage(to);
        log.setOperator(req.operator());
        log.setReason(req.reason());
        log.setCreatedAt(LocalDateTime.now().toString());
        logMapper.insert(log);                              // append：与主表同事务，不留痕即不算流转

        return toVO(e, siteNames(List.of(siteNo)).get(siteNo));
    }

    private LocSiteLifecycle byNo(String siteNo) {
        if (siteNo == null || siteNo.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<LocSiteLifecycle>()
                .eq(LocSiteLifecycle::getSiteNo, siteNo).last("limit 1"));
    }

    /** 批量取站点展示名（一次 IN 查询，避免列表逐行 N+1）。 */
    private Map<String, String> siteNames(List<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        List<LocSite> sites = siteMapper.selectList(
                new LambdaQueryWrapper<LocSite>().in(LocSite::getSiteNo, siteNos));
        return sites.stream()
                .filter(s -> s.getSiteNo() != null && s.getName() != null)
                .collect(Collectors.toMap(LocSite::getSiteNo, LocSite::getName, (a, b) -> a));
    }

    private static SiteLifecycle toVO(LocSiteLifecycle e, String siteName) {
        return new SiteLifecycle(e.getSiteNo(), siteName, e.getStage(), e.getStageAt(),
                e.getOwner(), e.getCurrency(), e.getGmvLtm());
    }
}
