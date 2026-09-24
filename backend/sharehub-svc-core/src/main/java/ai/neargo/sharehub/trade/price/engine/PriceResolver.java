package ai.neargo.sharehub.trade.price.engine;

import ai.neargo.sharehub.trade.price.PricePlanStatus;

import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PricePlanScope;
import ai.neargo.sharehub.trade.price.entity.ScopeLevel;
import ai.neargo.sharehub.trade.price.mapper.PricePlanMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanScopeMapper;
import ai.neargo.sharehub.trade.price.mapper.PriceLadderMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanItemMapper;
import ai.neargo.sharehub.trade.price.entity.PriceLadder;
import ai.neargo.sharehub.trade.price.entity.PricePlanItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 取价链：按**适用范围**（{@code price_plan_scope}）定位方案，并展开成引擎可直接吃的规格。
 *
 * <h3>2026-09-23 重写（ADR-028 / TDD-P1）</h3>
 * 此前读的是 {@code price_rule}（「差异化定价」菜单），而运营在方案上配的「适用范围」
 * 没有任何人读。动手时又实测到：下单处调的是 {@code resolve(POWERBANK, null, null)} ——
 * <b>站点压根没传进来</b>，于是 {@code price_rule} 那套也从未生效。
 * 也就是说改写之前，**站点级差价完全不存在，两个菜单都是摆设**。
 * 现在只有一处答案：{@code price_plan_scope}。
 *
 * <h3>裁决顺序（ADR-028 §二）</h3>
 * <ol>
 *   <li><b>硬过滤</b>：{@code device_type} 相符、生效期覆盖取价时刻、方案 {@code status=ACTIVE}</li>
 *   <li><b>过滤器相符</b>：{@code vendor_code}/{@code model}/{@code brand_no} 要么为空（不限），要么等于入参</li>
 *   <li>取 <b>层序最小</b>（最具体）的：{@link ScopeLevel} 的声明顺序即优先级</li>
 *   <li>同层内，<b>过滤器命中数多</b>的优先 —— 「本站点 × 厂商 X」比「本站点」更具体</li>
 *   <li>仍并列：{@code priority} 降序 → {@code id} 大者（最新）</li>
 * </ol>
 *
 * <h3>⚠️ 匹配不到必须抛异常，绝不能返回「空规格」</h3>
 * 空规格会让引擎算出 0 元 —— 那是<b>静默免单</b>。本项目已经栽过一次完全同型的错误：
 * {@code durationMinutes} 用 {@code catch → return 0}，导致每一单时长都是 0、
 * 费用都是 0，且没有任何报错。<b>静默少收钱比报错难查得多</b>，
 * 所以这里宁可结算失败（有人会立刻发现），也不静默收 0。
 *
 * <h3>展开而非引用</h3>
 * 返回的是**值**（{@link PriceItemSpec} 列表），不是 {@code planNo}。
 * 订单落库时把它整体快照进 {@code ord_order.price_snapshot} ——
 * 之后改价不影响在途单，这是既有纪律。命中原因（层/引用/过滤器）也一并进快照，
 * 结算争议时「为什么这单按这个价」从快照读，不回头重算（ADR-028 §五）。
 */
@Component
public class PriceResolver {

    private final PricePlanScopeMapper scopes;
    private final PricePlanMapper plans;
    private final PricePlanItemMapper items;
    private final PriceLadderMapper ladders;

    public PriceResolver(PricePlanScopeMapper scopes, PricePlanMapper plans,
                         PricePlanItemMapper items, PriceLadderMapper ladders) {
        this.scopes = scopes;
        this.plans = plans;
        this.items = items;
        this.ladders = ladders;
    }

    /**
     * 解析出适用的计价规格。
     *
     * @throws IllegalStateException 匹配不到任何方案 —— 见类注释「必须抛异常」
     */
    public Resolved resolve(PriceQuery q) {
        Hit hit = match(q);
        if (hit == null) {
            throw new IllegalStateException(
                    "未匹配到计价方案：" + q + "。**拒绝结算而非按 0 收费** —— 静默免单无法被发现。"
                            + " 至少要为设备类型 " + q.deviceType() + " 配一条 ALL 层的默认方案。");
        }
        PricePlan plan = plans.selectOne(new LambdaQueryWrapper<PricePlan>()
                .eq(PricePlan::getPlanNo, hit.planNo()).last("limit 1"));
        if (plan == null) {
            throw new IllegalStateException("适用范围指向的方案不存在: " + hit.planNo());
        }
        return new Resolved(hit.planNo(), plan.getCurrency(), expand(hit.planNo()), hit, null);
    }

    /**
     * 按 ADR-028 §二裁决。无命中返回 null（由 {@link #resolve} 决定怎么处理）。
     *
     * <p>一次把候选全读回来再在内存里排序，而不是拼一条大 SQL：
     * 范围表是**配置量级**（几十到几百行），而层序 + 过滤器命中数这种裁决用 SQL 表达
     * 会得到一条没人能读懂也没人敢改的语句。
     *
     * <p>{@code public} 是为了让裁决顺序能被单测穷举（{@code PriceScopeResolveTest}）——
     * 顺序错了不抛异常、不告警，只会静默按另一个方案收钱，必须有测试钉住。
     */
    public Hit match(PriceQuery q) {
        if (q.deviceType() == null || q.deviceType().isBlank()) return null;
        LocalDateTime at = q.at() == null ? LocalDateTime.now() : q.at();

        List<PricePlanScope> rows = scopes.selectList(new LambdaQueryWrapper<PricePlanScope>()
                .eq(PricePlanScope::getDeviceType, q.deviceType()));
        if (rows.isEmpty()) return null;

        // 只有 ACTIVE 的方案能被选中。一次取回方案状态，避免逐行回表（N+1）。
        Set<String> active = plans.selectList(new LambdaQueryWrapper<PricePlan>()
                        .eq(PricePlan::getStatus, PricePlanStatus.ACTIVE.name()))
                .stream().map(PricePlan::getPlanNo).collect(Collectors.toSet());

        record Cand(PricePlanScope row, ScopeLevel level, int filters) {
        }
        List<Cand> cands = new ArrayList<>();
        for (PricePlanScope r : rows) {
            // 设备类型在 SQL 里已经过滤过一次，这里**再判一次**：SQL 那道是为了少读行（优化），
            // 规则本身必须由裁决逻辑自己守住。否则哪天查询条件被改宽（或换了读法），
            // 按摩椅就会拿到充电宝的价，而且一声不响 —— 正是这次要修的缺陷 1。
            if (!q.deviceType().equals(r.getDeviceType())) continue;
            if (!active.contains(r.getPlanNo())) continue;
            if (!withinEffective(r, at)) continue;
            ScopeLevel level = ScopeLevel.of(r.getScopeType()).orElse(null);
            if (level == null) continue;                 // 存量脏值不该让整条取价失败
            String want = q.refOf(level);
            if (want == null) continue;                  // 这一层无从判断 → 不参与，**不降级成通配**
            if (!want.equals(r.getScopeRef())) continue;
            int filters = filterHits(r, q);
            if (filters < 0) continue;                   // 过滤器与本次查询不符
            cands.add(new Cand(r, level, filters));
        }
        return cands.stream()
                .max(Comparator
                        .<Cand>comparingInt(c -> -c.level().ordinal())   // ① 层序小者优先
                        .thenComparingInt(Cand::filters)                  // ② 过滤器命中多者优先
                        .thenComparingInt(c -> c.row().getPriority() == null ? 0 : c.row().getPriority())
                        .thenComparingLong(c -> c.row().getId() == null ? 0L : c.row().getId()))
                .map(c -> new Hit(c.row().getPlanNo(), c.level().name(), c.row().getScopeRef(),
                        filterDesc(c.row()), null))
                .orElse(null);
    }

    /** 生效期覆盖取价时刻。两端可空（空 = 不限），闭区间。 */
    private static boolean withinEffective(PricePlanScope r, LocalDateTime at) {
        if (r.getEffectiveFrom() != null && at.isBefore(r.getEffectiveFrom())) return false;
        return r.getEffectiveTo() == null || !at.isAfter(r.getEffectiveTo());
    }

    /**
     * 过滤器命中数；{@code -1} 表示与本次查询不符（该行出局）。
     *
     * <p>「空 = 不限」而不是「空 = 只匹配空」：范围行上不填厂商，意思是这一条对所有厂商都算数。
     */
    private static int filterHits(PricePlanScope r, PriceQuery q) {
        int n = 0;
        int a = one(r.getVendorCode(), q.vendorCode());
        int b = one(r.getModel(), q.model());
        int c = one(r.getBrandNo(), q.brandNo());
        if (a < 0 || b < 0 || c < 0) return -1;
        n = a + b + c;
        return n;
    }

    private static int one(String want, String got) {
        if (want == null || want.isBlank()) return 0;    // 不限
        return want.equals(got) ? 1 : -1;
    }

    private static String filterDesc(PricePlanScope r) {
        List<String> ps = new ArrayList<>();
        if (r.getVendorCode() != null && !r.getVendorCode().isBlank()) ps.add("vendor=" + r.getVendorCode());
        if (r.getModel() != null && !r.getModel().isBlank()) ps.add("model=" + r.getModel());
        if (r.getBrandNo() != null && !r.getBrandNo().isBlank()) ps.add("brand=" + r.getBrandNo());
        return String.join(",", ps);
    }

    /**
     * 命中原因 —— 进订单快照（ADR-028 §五）。
     *
     * <p>结算争议时要能回答「为什么这单按这个价」；OM-S3 的试算器也要显示命中原因。
     * 两者都从快照读，**不回头重算** —— 重算会用今天的配置解释昨天的订单。
     *
     * @param multiplier 时段倍率；下单时解析、快照定格（见 PriceMultiplierResolver）
     */
    public record Hit(String planNo, String level, String ref, String filters, BigDecimal multiplier) {

        public Hit withMultiplier(BigDecimal m) {
            return new Hit(planNo, level, ref, filters, m);
        }
    }

    /** 把方案展开成引擎规格（费用项 + 各自的阶梯）。 */
    public List<PriceItemSpec> expand(String planNo) {
        List<PricePlanItem> its = items.selectList(new LambdaQueryWrapper<PricePlanItem>()
                .eq(PricePlanItem::getPlanNo, planNo)
                .eq(PricePlanItem::getStatus, "ENABLED")
                .orderByAsc(PricePlanItem::getSort));
        if (its.isEmpty()) {
            throw new IllegalStateException("方案没有任何费用项，无法计价: " + planNo);
        }

        // 一次取回全部阶梯再分组，避免逐项回表（N+1）
        List<String> itemNos = its.stream().map(PricePlanItem::getItemNo).toList();
        Map<String, List<PriceLadder>> byItem = ladders.selectList(
                        new LambdaQueryWrapper<PriceLadder>().in(PriceLadder::getItemNo, itemNos))
                .stream().collect(Collectors.groupingBy(PriceLadder::getItemNo));

        return its.stream().map(i -> new PriceItemSpec(
                i.getItemType(), i.getMetering(), i.getFreeQty(), i.getUnitQty(), i.getRounding(),
                byItem.getOrDefault(i.getItemNo(), List.of()).stream()
                        .sorted(Comparator.comparing(PriceLadder::getSeq))
                        .map(l -> new PriceItemSpec.PriceLadderSpec(
                                l.getFromQty(), l.getToQty(), l.getUnitPrice()))
                        .toList(),
                i.getCapDaily())).toList();
    }

    /**
     * 解析结果。{@link #specs} 会被整体快照进订单，故必须是**值**而非引用。
     *
     * @param planNo     命中的方案（仅作留痕，计费不再回读它）
     * @param currency   币种
     * @param specs      展开后的费用项规格
     * @param hit        命中原因（层 / 引用 / 过滤器 / 倍率），进快照供事后解释
     * @param multiplier 时段倍率；{@code null} 表示本单不加倍
     */
    public record Resolved(String planNo, String currency, List<PriceItemSpec> specs,
                           Hit hit, BigDecimal multiplier) {

        /** 带上时段倍率（下单时解析一次，随快照定格）。 */
        public Resolved withMultiplier(BigDecimal m) {
            return new Resolved(planNo, currency, specs,
                    hit == null ? null : hit.withMultiplier(m), m);
        }

        /** 序列化为订单快照。改价不影响在途单的实现手段。 */
        public String toSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("planNo", planNo);
            m.put("currency", currency);
            m.put("items", specs);
            if (hit != null) {
                // 命中原因不是可选装饰：没有它，事后只能用**今天的配置**去解释昨天的订单。
                m.put("hit", Map.of(
                        "level", str(hit.level()), "ref", str(hit.ref()),
                        "filters", str(hit.filters()), "planNo", str(hit.planNo()),
                        "multiplier", hit.multiplier() == null ? "" : hit.multiplier().toPlainString()));
            }
            return Json.write(m);
        }

        private static String str(String s) {
            return s == null ? "" : s;
        }
    }

    /** 从快照读回时段倍率 —— 结算时用它，**不重新解析时段**（跨时段长单不分段，ADR-028 §四）。 */
    @SuppressWarnings("unchecked")
    public static BigDecimal multiplierFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        Map<String, Object> m = Json.read(snapshot, Map.class);
        if (m == null) return null;
        if (!(m.get("hit") instanceof Map<?, ?> hit)) return null;
        Object v = hit.get("multiplier");
        String s = v == null ? "" : String.valueOf(v);
        return s.isBlank() ? null : new BigDecimal(s);
    }

    /** 从订单快照还原规格 —— 结算时用它，**不回读 price_plan**。 */
    @SuppressWarnings("unchecked")
    public static List<PriceItemSpec> fromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return List.of();
        Map<String, Object> m = Json.read(snapshot, Map.class);
        if (m == null) return List.of();
        Object raw = m.get("items");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(o -> {
            Map<String, Object> i = (Map<String, Object>) o;
            List<PriceItemSpec.PriceLadderSpec> ls = ((List<Map<String, Object>>)
                    i.getOrDefault("ladders", List.of())).stream()
                    .map(l -> new PriceItemSpec.PriceLadderSpec(
                            dec(l.get("fromQty")), dec(l.get("toQty")), dec(l.get("unitPrice"))))
                    .toList();
            return new PriceItemSpec((String) i.get("itemType"), (String) i.get("metering"),
                    dec(i.get("freeQty")), dec(i.get("unitQty")), (String) i.get("rounding"),
                    ls, dec(i.get("capDaily")));
        }).toList();
    }

    private static BigDecimal dec(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }
}
