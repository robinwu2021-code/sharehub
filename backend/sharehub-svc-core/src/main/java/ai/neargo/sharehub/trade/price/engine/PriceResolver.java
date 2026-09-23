package ai.neargo.sharehub.trade.price.engine;

import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PriceRule;
import ai.neargo.sharehub.trade.price.mapper.PricePlanMapper;
import ai.neargo.sharehub.trade.price.mapper.PriceRuleMapper;
import ai.neargo.sharehub.trade.price.mapper.PriceLadderMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanItemMapper;
import ai.neargo.sharehub.trade.price.entity.PriceLadder;
import ai.neargo.sharehub.trade.price.entity.PricePlanItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 取价链：按「设备类型 + 站点 + 场景」定位方案，并**展开成引擎可直接吃的规格**。
 *
 * <h3>四层匹配（越具体优先级越高）</h3>
 * <ol>
 *   <li>站点专属规则（{@code price_rule.site_no} 命中）</li>
 *   <li>场景规则（{@code scene_type} 命中，如「商场」「医院」）</li>
 *   <li>设备类型的默认方案（{@code price_plan.device_type}）</li>
 *   <li>通用方案（{@code device_type IS NULL}）</li>
 * </ol>
 * 同层多条时按 {@code priority} 降序取第一条。
 *
 * <h3>⚠️ 匹配不到必须抛异常，绝不能返回「空规格」</h3>
 * 空规格会让引擎算出 0 元 —— 那是**静默免单**。本项目已经栽过一次完全同型的错误：
 * {@code durationMinutes} 用 {@code catch → return 0}，导致每一单时长都是 0、
 * 费用都是 0，且没有任何报错。<b>静默少收钱比报错难查得多</b>，
 * 所以这里宁可结算失败（有人会立刻发现），也不静默收 0。
 *
 * <h3>展开而非引用</h3>
 * 返回的是**值**（{@link PriceItemSpec} 列表），不是 {@code planNo}。
 * 订单落库时把它整体快照进 {@code ord_order.price_snapshot} ——
 * 之后改价不影响在途单，这是既有纪律。
 */
@Component
public class PriceResolver {

    private final PriceRuleMapper rules;
    private final PricePlanMapper plans;
    private final PricePlanItemMapper items;
    private final PriceLadderMapper ladders;

    public PriceResolver(PriceRuleMapper rules, PricePlanMapper plans,
                         PricePlanItemMapper items, PriceLadderMapper ladders) {
        this.rules = rules;
        this.plans = plans;
        this.items = items;
        this.ladders = ladders;
    }

    /**
     * 解析出适用的计价规格。
     *
     * @param deviceType 设备类型，如 POWERBANK
     * @param siteNo     站点；可空
     * @param sceneType  场景；可空
     * @throws IllegalStateException 匹配不到任何方案 —— 见类注释「必须抛异常」
     */
    public Resolved resolve(String deviceType, String siteNo, String sceneType) {
        String planNo = matchPlanNo(deviceType, siteNo, sceneType);
        if (planNo == null) {
            throw new IllegalStateException(
                    "未匹配到计价方案：deviceType=" + deviceType + " site=" + siteNo
                            + " scene=" + sceneType + "。**拒绝结算而非按 0 收费** —— 静默免单无法被发现。");
        }
        PricePlan plan = plans.selectOne(new LambdaQueryWrapper<PricePlan>()
                .eq(PricePlan::getPlanNo, planNo).last("limit 1"));
        if (plan == null) {
            throw new IllegalStateException("计价规则指向的方案不存在: " + planNo);
        }
        return new Resolved(planNo, plan.getCurrency(), expand(planNo));
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

    /** 四层匹配，返回命中的 planNo；无命中返回 null（由调用方决定怎么处理）。 */
    private String matchPlanNo(String deviceType, String siteNo, String sceneType) {
        List<PriceRule> all = rules.selectList(new LambdaQueryWrapper<PriceRule>()
                .orderByDesc(PriceRule::getPriority));

        // ① 站点专属
        String hit = firstMatch(all, r -> siteNo != null && siteNo.equals(r.getSiteNo()));
        if (hit != null) return hit;

        // ② 场景
        hit = firstMatch(all, r -> sceneType != null && sceneType.equals(r.getSceneType())
                && r.getSiteNo() == null);
        if (hit != null) return hit;

        // ③④ 设备类型默认 / 通用：直接从方案表取，规则表不必为「默认」配一条
        List<PricePlan> ps = plans.selectList(new LambdaQueryWrapper<PricePlan>()
                /*
                 * 状态取值统一为 ACTIVE / DISABLED（与 `price_plan` 的 DDL 默认值、
                 * 运营端契约一致）。此处原为 "ENABLED" —— 而 `PricePlanServiceImpl` 新建时写的是
                 * "ACTIVE"，于是**运营新建的方案永远不会被计价引擎选中**：
                 * 界面上方案好端端地列着、状态显示启用，订单却一律按兜底价计费。
                 * 今天没出事只是因为种子里那一行恰好是 "ENABLED"。V41 已归一存量数据。
                 */
                .eq(PricePlan::getStatus, "ACTIVE"));
        return ps.stream().filter(p -> deviceType != null && deviceType.equals(p.getDeviceType()))
                .map(PricePlan::getPlanNo).findFirst()
                .orElseGet(() -> ps.stream().filter(p -> p.getDeviceType() == null)
                        .map(PricePlan::getPlanNo).findFirst().orElse(null));
    }

    private String firstMatch(List<PriceRule> all, java.util.function.Predicate<PriceRule> p) {
        return all.stream().filter(p).map(PriceRule::getPlanNo).findFirst().orElse(null);
    }

    /**
     * 解析结果。{@link #specs} 会被整体快照进订单，故必须是**值**而非引用。
     *
     * @param planNo   命中的方案（仅作留痕，计费不再回读它）
     * @param currency 币种
     * @param specs    展开后的费用项规格
     */
    public record Resolved(String planNo, String currency, List<PriceItemSpec> specs) {

        /** 序列化为订单快照。改价不影响在途单的实现手段。 */
        public String toSnapshot() {
            return Json.write(Map.of("planNo", planNo, "currency", currency, "items", specs));
        }
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
