package ai.neargo.sharehub.wo.ext.service.impl;

import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.InspectionPlan;
import ai.neargo.sharehub.wo.ext.entity.WoInspectionPlan;
import ai.neargo.sharehub.wo.ext.mapper.WoInspectionPlanMapper;
import ai.neargo.sharehub.wo.ext.service.InspectionPlanService;
import org.springframework.stereotype.Service;

/**
 * 巡检计划实现。{@code run} 的机柜定位走 {@link CabinetQueryPort}（api 契约层）——
 * svc-ops 不 import svc-core，单体下由本地实现直连、拆分后自动换 HTTP（PortWiringTest 锁装配）。
 */
@Service
public class InspectionPlanServiceImpl extends AbstractCrudService<WoInspectionPlan, InspectionPlan>
        implements InspectionPlanService {

    private final WoInspectionPlanMapper planMapper;
    private final WoOpsService woOps;
    private final CabinetQueryPort cabinetQuery;

    public InspectionPlanServiceImpl(WoInspectionPlanMapper mapper, WoOpsService woOps,
                                     CabinetQueryPort cabinetQuery) {
        super(mapper);
        this.planMapper = mapper;
        this.woOps = woOps;
        this.cabinetQuery = cabinetQuery;
    }

    @Override
    protected String keyColumn() {
        return "plan_no";
    }

    @Override
    protected String keyOf(WoInspectionPlan e) {
        return e.getPlanNo();
    }

    @Override
    protected void setKey(WoInspectionPlan e, String no) {
        e.setPlanNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.INSPECTION;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"plan_no", "frequency", "assignee_id"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"active"};
    }

    @Override
    protected void beforeCreate(WoInspectionPlan e) {
        if (e.getActive() == null) e.setActive(1);
    }

    @Override
    protected void beforeUpdate(WoInspectionPlan e, WoInspectionPlan current) {
        if (e.getActive() == null) e.setActive(current.getActive());
        // nextAt 由调度器回写，业务面提交的值不作数（否则改一次计划就把下次执行时间抹了）
        if (e.getNextAt() == null) e.setNextAt(current.getNextAt());
    }

    @Override
    protected InspectionPlan toVO(WoInspectionPlan e) {
        java.util.List<String> woNos = e.getLastRunWoNos() == null || e.getLastRunWoNos().isBlank()
                ? java.util.List.of()
                : java.util.List.of(e.getLastRunWoNos().split(","));
        return new InspectionPlan(e.getPlanNo(), e.getRoute(), e.getFrequency(), e.getCron(),
                e.getNextAt(), e.getAssigneeNo(), e.getActive() != null && e.getActive() == 1,
                e.getLastRunAt() == null ? null : e.getLastRunAt().toString(),
                e.getLastRunPeriod(), woNos);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object run(String planNo) {
        WoInspectionPlan e = selectByKey(planNo);
        if (e == null) throw new IllegalArgumentException("巡检计划不存在: " + planNo);
        if (e.getActive() == null || e.getActive() != 1) {
            throw new IllegalArgumentException("计划已停用，启用后才能执行: " + planNo);
        }
        // 幂等键 = 周期键（与前端 inspectionPeriodKey 同口径）：同周期第二次执行整批拒
        String period = periodKey(e.getFrequency(), java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        if (period.equals(e.getLastRunPeriod())) {
            throw new IllegalArgumentException("本周期（" + period + "）已执行过，下个周期再试");
        }

        // 路线「A → B」拆站点，逐站定位一台在册机柜（缺一站则整批拒 —— 半跑会让巡检覆盖率成谜）
        java.util.List<String> stops = java.util.Arrays.stream(
                        (e.getRoute() == null ? "" : e.getRoute()).split("→"))
                .map(String::trim).filter(x -> !x.isBlank()).toList();
        if (stops.isEmpty()) throw new IllegalArgumentException("巡检路线为空，无法生成工单: " + planNo);
        java.util.Map<String, String> stopCabinet = new java.util.LinkedHashMap<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String stop : stops) {
            CabinetBrief cab = cabinetQuery.assignable(stop, null, 1).stream().findFirst().orElse(null);
            if (cab == null) missing.add(stop); else stopCabinet.put(stop, cab.cabinetNo());
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("路线站点「" + String.join("、", missing)
                    + "」无在册机柜，本次不生成任何工单");
        }

        // 一站一张巡检工单（source=PLAN 挂回计划号），生成即派给计划负责人（走状态机 dispatch）
        java.util.List<String> woNos = new java.util.ArrayList<>();
        for (var en : stopCabinet.entrySet()) {
            var wo = woOps.create(new WorkOrderDraft("INSPECT", "PLAN", planNo + ":" + period + ":" + en.getKey(),
                    "LOW", en.getValue(), null, en.getKey(), null, null,
                    "【巡检计划 " + planNo + "】" + e.getRoute() + " · " + en.getKey()
                            + " 例行巡检（" + e.getFrequency() + "）", e.getNextAt()));
            woOps.dispatch(wo.woNo(), e.getAssigneeNo());
            woNos.add(wo.woNo());
        }

        e.setLastRunAt(java.time.LocalDateTime.now());
        e.setLastRunPeriod(period);
        e.setLastRunWoNos(String.join(",", woNos));
        planMapper.updateById(e);
        return java.util.Map.of("planNo", planNo, "period", period, "woNos", woNos);
    }

    /** 周期键（与前端 {@code inspectionPeriodKey} 同口径）：每日按天/每周按周序/双周折半/每月按月。 */
    static String periodKey(String frequency, java.time.LocalDate at) {
        String day = at.toString();
        int week = at.getDayOfYear() / 7 + 1;
        return switch (frequency == null ? "" : frequency) {
            case "每日" -> day;
            case "每周" -> at.getYear() + "-W" + String.format("%02d", week);
            case "双周" -> at.getYear() + "-B" + String.format("%02d", (week + 1) / 2);
            case "每月" -> day.substring(0, 7);
            default -> day;   // 未知频率退化按天：宁可粒度细也不放开幂等
        };
    }
}
