package ai.neargo.sharehub.trade.price.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.price.entity.PriceAdjustment;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.mapper.PriceAdjustmentMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanMapper;
import ai.neargo.sharehub.trade.price.service.PriceAdjustmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 预约调价实现。
 *
 * <h2>两条不显然的规矩</h2>
 *
 * <ol>
 *   <li><b>恢复原价靠快照，不靠反推。</b> 生效那一刻把被改字段的原值存进 {@code beforeSnapshot}，
 *       恢复时只写回这几个字段。按 patch 反推会把调价期间别人对方案做的改动一起抹掉。</li>
 *   <li><b>恢复前先核对方案是否还是调价写进去的值。</b> 不一致说明期间有人手工改过 ——
 *       此时**不恢复**，置 FAILED 让人来看。悄悄覆盖别人的改动，比不恢复更难查。</li>
 * </ol>
 *
 * <p><b>可调字段只有五个</b>：改适用范围属于改方案本身，不该走调价 ——
 * 一个到期会自动改回的动作，不应该能改变「这个方案管哪些站点」。
 */
@Service
public class PriceAdjustmentServiceImpl implements PriceAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(PriceAdjustmentServiceImpl.class);

    /** 可调字段（与前端 `ADJUSTABLE` 一一对应）。`buyoutPrice` 在库里叫 `capTotal`。 */
    private static final List<String> FIELDS =
            List.of("freeMinutes", "unitMinutes", "unitPrice", "capDaily", "buyoutPrice");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PriceAdjustmentMapper mapper;
    private final PricePlanMapper plans;

    public PriceAdjustmentServiceImpl(PriceAdjustmentMapper mapper, PricePlanMapper plans) {
        this.mapper = mapper;
        this.plans = plans;
    }

    @Override
    public PageResult<Map<String, Object>> page(Integer page, Integer size, String keyword,
                                                String planNo, String status) {
        tick();   // 惰性执行：列表一打开就看到最新状态，不必等调度器那一分钟
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<PriceAdjustment> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(PriceAdjustment::getName, keyword)
                    .or().like(PriceAdjustment::getAdjustNo, keyword));
        }
        if (planNo != null && !planNo.isBlank()) w.eq(PriceAdjustment::getPlanNo, planNo);
        if (status != null && !status.isBlank()) w.eq(PriceAdjustment::getStatus, status);
        w.orderByDesc(PriceAdjustment::getId);
        Page<PriceAdjustment> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(this::toVO).toList(), r.getTotal());
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> body) {
        Map<String, Object> in = body == null ? Map.of() : body;
        String no = str(in.get("adjustNo"));
        PriceAdjustment e = no == null || no.isBlank() ? null : find(no);
        if (e != null && !"SCHEDULED".equals(e.getStatus())) {
            // 已生效/已撤销的不能改：界面上的历史会对不上账
            throw new IllegalArgumentException("只有待生效的调价单可以修改，当前状态：" + e.getStatus());
        }
        String planNo = str(in.get("planNo"));
        if (planNo == null || planNo.isBlank()) throw new IllegalArgumentException("请选择收费方案");
        PricePlan plan = planOf(planNo);
        if (plan == null) throw new IllegalArgumentException("收费方案不存在：" + planNo);

        Map<String, Object> patch = patchOf(in.get("patch"));
        if (patch.isEmpty()) throw new IllegalArgumentException("至少要改一个字段，否则这张调价单什么也不会做");
        LocalDateTime effectiveAt = time(in.get("effectiveAt"));
        if (effectiveAt == null) throw new IllegalArgumentException("请填写生效时间");
        LocalDateTime revertAt = time(in.get("revertAt"));
        if (revertAt != null && !revertAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("恢复时间必须晚于生效时间");
        }
        if (e == null) {
            e = new PriceAdjustment();
            e.setAdjustNo(nextNo());
            e.setTenantId("MAIN");
            e.setStatus("SCHEDULED");
        }
        e.setPlanNo(planNo);
        e.setName(str(in.get("name")) == null ? plan.getName() + " 调价" : str(in.get("name")));
        e.setPatch(write(patch));
        e.setEffectiveAt(effectiveAt);
        e.setRevertAt(revertAt);
        e.setReason(str(in.get("reason")));
        if (e.getId() == null) mapper.insert(e); else mapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(String adjustNo, String reason) {
        PriceAdjustment e = require(adjustNo);
        if (!"SCHEDULED".equals(e.getStatus())) {
            throw new IllegalArgumentException("「" + e.getStatus() + "」状态的调价单不能撤销，只有待生效可以");
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("请填写撤销原因");
        e.setStatus("CANCELLED");
        e.setReason(reason.trim());
        mapper.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public Map<String, Object> revert(String adjustNo) {
        PriceAdjustment e = require(adjustNo);
        if (!"APPLIED".equals(e.getStatus())) {
            throw new IllegalArgumentException("只有已生效的调价单可以恢复");
        }
        doRevert(e, LocalDateTime.now());
        return toVO(e);
    }

    @Override
    @Transactional
    public Map<String, Object> retry(String adjustNo) {
        PriceAdjustment e = require(adjustNo);
        if (!"FAILED".equals(e.getStatus())) {
            throw new IllegalArgumentException("只有执行失败的调价单需要重试");
        }
        LocalDateTime now = LocalDateTime.now();
        // 失败在哪一步，就从哪一步重来：还没生效过 → 重新生效；已生效过 → 重新恢复
        if (e.getAppliedAt() == null) {
            e.setStatus("SCHEDULED");
            e.setFailReason(null);
            mapper.updateById(e);
            doApply(e, now);
        } else {
            e.setStatus("APPLIED");
            e.setFailReason(null);
            mapper.updateById(e);
            doRevert(e, now);
        }
        return toVO(e);
    }

    @Override
    @Transactional
    public List<String> tick() {
        LocalDateTime now = LocalDateTime.now();
        List<String> touched = new ArrayList<>();
        for (PriceAdjustment a : mapper.selectList(new LambdaQueryWrapper<PriceAdjustment>()
                .in(PriceAdjustment::getStatus, List.of("SCHEDULED", "APPLIED")))) {
            if ("SCHEDULED".equals(a.getStatus()) && !a.getEffectiveAt().isAfter(now)) {
                doApply(a, now);
                touched.add(a.getAdjustNo());
            }
            /*
             * 生效与恢复在同一次 tick 里都可能发生 —— 服务停了一段时间之后补算时，
             * 一张「昨天生效、今早恢复」的单子必须两步都走完。
             * 所以这里不是 else if：用 else if 会让它停在 APPLIED，价格一直挂着活动价。
             */
            if ("APPLIED".equals(a.getStatus()) && a.getRevertAt() != null && !a.getRevertAt().isAfter(now)) {
                doRevert(a, now);
                if (!touched.contains(a.getAdjustNo())) touched.add(a.getAdjustNo());
            }
        }
        return touched;
    }

    // ——————————————————————— 内部 ———————————————————————

    private void doApply(PriceAdjustment a, LocalDateTime now) {
        PricePlan plan = planOf(a.getPlanNo());
        if (plan == null || plan.getArchivedAt() != null || !"ACTIVE".equals(plan.getStatus())) {
            fail(a, "目标方案不可用（不存在 / 已归档 / 已停用），调价未执行");
            return;
        }
        Map<String, Object> patch = patchOf(a.getPatch());
        // 生效那一刻快照原值 —— 恢复时只认它
        Map<String, Object> before = new LinkedHashMap<>();
        for (String f : FIELDS) if (patch.containsKey(f)) before.put(f, get(plan, f));
        applyTo(plan, patch);
        plans.updateById(plan);
        a.setBeforeSnapshot(write(before));
        a.setStatus("APPLIED");
        a.setAppliedAt(now);
        a.setFailReason(null);
        mapper.updateById(a);
        log.info("调价 {} 已生效：方案 {} {} → {}", a.getAdjustNo(), a.getPlanNo(), before, patch);
    }

    private void doRevert(PriceAdjustment a, LocalDateTime now) {
        PricePlan plan = planOf(a.getPlanNo());
        if (plan == null) {
            fail(a, "目标方案不存在，无法恢复");
            return;
        }
        Map<String, Object> patch = patchOf(a.getPatch());
        // 方案还是不是调价写进去的值？不是就说明期间有人手工改过
        for (String f : FIELDS) {
            if (!patch.containsKey(f)) continue;
            if (!eq(get(plan, f), patch.get(f))) {
                fail(a, "方案在调价期间被人工修改过，请人工确认后处理，避免覆盖别人的改动");
                return;
            }
        }
        applyTo(plan, patchOf(a.getBeforeSnapshot()));
        plans.updateById(plan);
        a.setStatus("REVERTED");
        a.setRevertedAt(now);
        a.setFailReason(null);
        mapper.updateById(a);
        log.info("调价 {} 已恢复原价：方案 {}", a.getAdjustNo(), a.getPlanNo());
    }

    private void fail(PriceAdjustment a, String why) {
        a.setStatus("FAILED");
        a.setFailReason(why);
        mapper.updateById(a);
        log.warn("调价 {} 执行失败：{}", a.getAdjustNo(), why);
    }

    private static void applyTo(PricePlan plan, Map<String, Object> patch) {
        for (String f : FIELDS) {
            if (!patch.containsKey(f) || patch.get(f) == null) continue;
            set(plan, f, patch.get(f));
        }
    }

    private static Object get(PricePlan p, String field) {
        return switch (field) {
            case "freeMinutes" -> p.getFreeMinutes();
            case "unitMinutes" -> p.getUnitMinutes();
            case "unitPrice" -> p.getUnitPrice();
            case "capDaily" -> p.getCapDaily();
            case "buyoutPrice" -> p.getCapTotal();   // 买断价在库里叫 cap_total
            default -> null;
        };
    }

    private static void set(PricePlan p, String field, Object v) {
        switch (field) {
            case "freeMinutes" -> p.setFreeMinutes(num(v).intValue());
            case "unitMinutes" -> p.setUnitMinutes(num(v).intValue());
            case "unitPrice" -> p.setUnitPrice(dec(v));
            case "capDaily" -> p.setCapDaily(dec(v));
            case "buyoutPrice" -> p.setCapTotal(dec(v));
            default -> { }
        }
    }

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == b;
        return dec(a).compareTo(dec(b)) == 0;
    }

    private static Number num(Object v) {
        return v instanceof Number n ? n : new BigDecimal(String.valueOf(v));
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private PricePlan planOf(String planNo) {
        return plans.selectOne(new LambdaQueryWrapper<PricePlan>()
                .eq(PricePlan::getPlanNo, planNo).last("limit 1"));
    }

    private PriceAdjustment find(String no) {
        return mapper.selectOne(new LambdaQueryWrapper<PriceAdjustment>()
                .eq(PriceAdjustment::getAdjustNo, no).last("limit 1"));
    }

    private PriceAdjustment require(String no) {
        PriceAdjustment e = find(no);
        if (e == null) throw new IllegalArgumentException("调价单不存在：" + no);
        return e;
    }

    /** 扫同前缀最大号 +1（[db-design §1.4.1]，禁止「前缀 + 集合长度」）。 */
    private String nextNo() {
        int max = 0;
        for (PriceAdjustment a : mapper.selectList(new LambdaQueryWrapper<>())) {
            String n = a.getAdjustNo();
            if (n == null || !n.startsWith("PA")) continue;
            try {
                max = Math.max(max, Integer.parseInt(n.substring(2)));
            } catch (NumberFormatException ignored) {
                // 手工造的号不参与取号
            }
        }
        return "PA" + String.format("%04d", max + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> patchOf(Object v) {
        if (v == null) return Map.of();
        try {
            Map<String, Object> raw = v instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : JSON.readValue(String.valueOf(v), Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            // 只认白名单字段：多传的一律忽略，而不是写进方案里
            for (String f : FIELDS) if (raw.get(f) != null) out.put(f, raw.get(f));
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("调价内容格式不对：" + e.getMessage());
        }
    }

    private static String write(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDateTime time(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        String s = String.valueOf(v).trim();
        try {
            // 前端传 UTC ISO（2026-07-11T12:00:00Z 或不带 Z）
            if (s.endsWith("Z")) return LocalDateTime.ofInstant(java.time.Instant.parse(s), java.time.ZoneOffset.UTC);
            return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("时间格式不对：" + s);
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC).toString();
    }

    /** 出参逐字段对齐前端 `PriceAdjustment`。 */
    private Map<String, Object> toVO(PriceAdjustment e) {
        PricePlan plan = planOf(e.getPlanNo());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adjustNo", e.getAdjustNo());
        m.put("planNo", e.getPlanNo());
        m.put("planName", plan == null ? e.getPlanNo() : plan.getName());
        m.put("name", e.getName());
        m.put("patch", patchOf(e.getPatch()));
        m.put("beforeSnapshot", e.getBeforeSnapshot() == null ? null : patchOf(e.getBeforeSnapshot()));
        m.put("effectiveAt", iso(e.getEffectiveAt()));
        m.put("revertAt", iso(e.getRevertAt()));
        m.put("reason", e.getReason());
        m.put("status", e.getStatus());
        m.put("appliedAt", iso(e.getAppliedAt()));
        m.put("revertedAt", iso(e.getRevertedAt()));
        m.put("failReason", e.getFailReason());
        m.put("createdBy", e.getCreatedBy());
        m.put("createdAt", iso(e.getCreatedAt()));
        return m;
    }
}
