package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.DisposalKind;
import ai.neargo.sharehub.inv.dto.InventoryReportDtos.InventoryReport;
import ai.neargo.sharehub.inv.dto.InventoryReportDtos.PurchaseRow;
import ai.neargo.sharehub.inv.dto.InventoryReportDtos.WarehouseRow;
import ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow;
import ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThreshold;
import ai.neargo.sharehub.inv.entity.InvDisposal;
import ai.neargo.sharehub.inv.entity.InvIntake;
import ai.neargo.sharehub.inv.entity.InvIntakeItem;
import ai.neargo.sharehub.inv.mapper.InvDisposalMapper;
import ai.neargo.sharehub.inv.mapper.InvIntakeItemMapper;
import ai.neargo.sharehub.inv.mapper.InvIntakeMapper;
import ai.neargo.sharehub.inv.service.InventoryReportService;
import ai.neargo.sharehub.inv.service.StockThresholdService;
import ai.neargo.sharehub.inv.service.StockViewService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 库存报表（V206），口径见 {@link InventoryReportService}。 */
@Service
public class InventoryReportServiceImpl implements InventoryReportService {

    private final StockViewService stock;
    private final StockThresholdService thresholds;
    private final InvDisposalMapper disposals;
    private final InvIntakeMapper intakes;
    private final InvIntakeItemMapper intakeItems;

    public InventoryReportServiceImpl(StockViewService stock, StockThresholdService thresholds, InvDisposalMapper disposals,
                                      InvIntakeMapper intakes, InvIntakeItemMapper intakeItems) {
        this.stock = stock;
        this.thresholds = thresholds;
        this.disposals = disposals;
        this.intakes = intakes;
        this.intakeItems = intakeItems;
    }

    @Override
    public InventoryReport report(LocalDate from, LocalDate to, String warehouseNo) {
        LocalDate t = to == null ? LocalDate.now() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) throw BizException.badRequest("error.common.invalid_value", "from");
        if (ChronoUnit.DAYS.between(f, t) > 366) throw BizException.badRequest("error.common.invalid_value", "to");
        String wh = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();

        Map<String, StockThreshold> th = thresholds.list(wh).stream()
                .collect(Collectors.toMap(x -> x.warehouseNo() + ":" + x.itemType(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, StockPeriodRow> period = stock.periodSummary(f, t, wh).stream()
                .collect(Collectors.toMap(x -> x.warehouseNo() + ":" + x.itemType(), Function.identity(), (a, b) -> a));
        List<WarehouseRow> rows = new ArrayList<>();
        for (StockThreshold x : th.values()) {
            StockPeriodRow p = period.remove(x.warehouseNo() + ":" + x.itemType());
            rows.add(new WarehouseRow(x.warehouseNo(), x.warehouseName(), x.itemType(), p == null ? 0 : p.opening(), p == null ? 0 : p.inQty(),
                    p == null ? 0 : p.outQty(), p == null ? x.available() : p.closing(), x.available(), x.minQty(), x.low()));
        }
        // 期间里有流水、但仓已归档的：照样列出（没有安全库存）
        for (StockPeriodRow p : period.values()) {
            rows.add(new WarehouseRow(p.warehouseNo(), p.warehouseNo(), p.itemType(), p.opening(), p.inQty(), p.outQty(), p.closing(), 0, 0, false));
        }

        LambdaQueryWrapper<InvDisposal> dq = new LambdaQueryWrapper<InvDisposal>()
                .ge(InvDisposal::getCreatedAt, f.atStartOfDay()).lt(InvDisposal::getCreatedAt, t.plusDays(1).atStartOfDay());
        if (wh != null) dq.eq(InvDisposal::getWarehouseNo, wh);
        Map<String, Long> disp = new TreeMap<>();
        for (InvDisposal d : disposals.selectList(dq)) {
            String key = DisposalKind.SCRAP.name().equals(d.getKind()) ? d.getKind() + ":" + d.getReasonCode() : d.getKind();
            disp.merge(key, (long) (d.getItemCount() == null ? 0 : d.getItemCount()), Long::sum);
        }

        LambdaQueryWrapper<InvIntake> iq = new LambdaQueryWrapper<InvIntake>().isNotNull(InvIntake::getCurrency)
                .ge(InvIntake::getArrivedAt, f.atStartOfDay()).lt(InvIntake::getArrivedAt, t.plusDays(1).atStartOfDay());
        if (wh != null) iq.eq(InvIntake::getWarehouseNo, wh);
        Map<String, String> currencyOf = intakes.selectList(iq).stream()
                .collect(Collectors.toMap(InvIntake::getIntakeNo, InvIntake::getCurrency));
        Map<String, long[]> qty = new TreeMap<>();
        Map<String, BigDecimal> amount = new TreeMap<>();
        if (!currencyOf.isEmpty()) {
            for (InvIntakeItem i : intakeItems.selectList(new LambdaQueryWrapper<InvIntakeItem>()
                    .in(InvIntakeItem::getIntakeNo, currencyOf.keySet()).isNotNull(InvIntakeItem::getUnitCost))) {
                String cur = currencyOf.get(i.getIntakeNo());
                qty.computeIfAbsent(cur, k -> new long[1])[0]++;
                amount.merge(cur, i.getUnitCost(), BigDecimal::add);
            }
        }
        List<PurchaseRow> purchases = qty.keySet().stream().filter(Objects::nonNull)
                .map(c -> new PurchaseRow(c, qty.get(c)[0], amount.get(c))).toList();

        return new InventoryReport(f.toString(), t.toString(), stock.stages(), rows, disp, purchases);
    }
}
