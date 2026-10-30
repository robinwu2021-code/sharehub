package ai.neargo.sharehub.dev.movement;

import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceMovement;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备履历的唯一写口（PB-F1，V196）。与业务写同事务调用：业务回滚，履历随之回滚。
 *
 * <p>位置用带前缀的引用（{@link #wh} / {@link #agent} / {@link #site} / {@link #loc}），
 * 一列就能看出「从哪到哪」，不用再拼四五个可空列。
 */
@Component
public class MovementRecorder {

    public static final String CABINET = "CABINET";
    public static final String POWERBANK = "POWERBANK";
    public static final String TRANSIT = "TRANSIT";
    public static final String OUT = "OUT";

    private final DevMovementLogMapper logs;

    public MovementRecorder(DevMovementLogMapper logs) {
        this.logs = logs;
    }

    /** 一次变化：from / to 用下面几个前缀方法拼；没有的传 null。 */
    public record Move(String itemType, String itemNo, MovementEvent event, String fromRef, String toRef,
                       String agentBefore, String agentAfter, String reason, String sourceRef) {
    }

    public void record(Move m) {
        recordRetirement(m,null,null,null);
    }

    /** 报废前实际归属，作为终态范围快照；不要根据机柜的未来归属推导历史。 */
    public void recordRetirement(Move m,String regionId,String siteNo,String agentNo) {
        DevMovementLog e = new DevMovementLog();
        e.setTenantId("MAIN");
        e.setItemType(m.itemType());
        e.setItemNo(m.itemNo());
        e.setEvent(m.event().name());
        e.setFromRef(m.fromRef());
        e.setToRef(m.toRef());
        e.setAgentBefore(m.agentBefore());
        e.setAgentAfter(m.agentAfter());
        e.setReason(m.reason() == null || m.reason().isBlank() ? null : truncate(m.reason().trim(), 512));
        e.setSourceRef(m.sourceRef());
        e.setScopeRegionId(regionId);e.setScopeSiteNo(siteNo);e.setScopeAgentNo(agentNo);
        String op = SecurityUtils.userNo();
        e.setOperatorNo(op == null ? "SYSTEM" : op);
        e.setOccurredAt(LocalDateTime.now());
        logs.insert(e);
    }

    /** 当前流转的最后一条履历；用于受信业务来源校验，不接受任意历史成员关系。 */
    public DevMovementLog latestForUpdate(String itemType, String itemNo) {
        return logs.selectOne(new LambdaQueryWrapper<DevMovementLog>().eq(DevMovementLog::getItemType,itemType)
                .eq(DevMovementLog::getItemNo,itemNo).orderByDesc(DevMovementLog::getId).last("limit 1 for update"));
    }

    /** 先找实际最近一次发货，再由调用方检查其单据范围，旧单据不能授权新流转。 */
    public java.util.Map<String,String> latestShipments(java.util.Collection<String> itemNos) {
        java.util.Map<String,String> result=new java.util.HashMap<>();
        if(itemNos.isEmpty()) return result;
        var query=new LambdaQueryWrapper<DevMovementLog>()
                .eq(DevMovementLog::getItemType,POWERBANK).eq(DevMovementLog::getEvent,MovementEvent.SHIP.name())
                .in(DevMovementLog::getItemNo,itemNos).orderByDesc(DevMovementLog::getId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            query.last("for update"); // 设备行锁之后使用当前读，不能读取事务早期快照。
        for(DevMovementLog row:logs.selectList(query))
            if(!result.containsKey(row.getItemNo())) result.put(row.getItemNo(),row.getSourceRef());
        return result;
    }

    /** 报废终态按当时归属；旧仓库处置履历仍按明确的WH来源授权，未知历史不猜测。 */
    public List<String> visibleRetiredPowerbanks(List<String> warehouses) {
        var scope=ai.neargo.sharehub.auth.StaffContext.dataScope();
        java.util.Map<String,DevMovementLog> latest=new java.util.LinkedHashMap<>();
        for(var row:logs.selectList(new LambdaQueryWrapper<DevMovementLog>()
                .eq(DevMovementLog::getItemType,POWERBANK).eq(DevMovementLog::getEvent,MovementEvent.RETIRE.name())
                .orderByDesc(DevMovementLog::getId))) latest.putIfAbsent(row.getItemNo(),row);
        return latest.values().stream().filter(row->{
            if(scope==null || scope.all()) return true;
            boolean snapshot=row.getScopeRegionId()!=null || row.getScopeSiteNo()!=null || row.getScopeAgentNo()!=null;
            if(!snapshot) return warehouses!=null && warehouses.stream().map(MovementRecorder::wh).anyMatch(ref->ref.equals(row.getFromRef()));
            return scope.rules().stream().anyMatch(rule->{
                String value=switch(rule.dim()) {
                    case "REGION" -> row.getScopeRegionId();
                    case "SITE" -> row.getScopeSiteNo();
                    case "AGENT" -> row.getScopeAgentNo();
                    default -> null;
                };
                return value!=null && rule.refs().contains(value);
            });
        }).map(DevMovementLog::getItemNo).toList();
    }

    /** 某台设备的履历，新的在前（最多 500 行）。 */
    public List<DeviceMovement> of(String itemNo) {
        if (itemNo == null || itemNo.isBlank()) return List.of();
        return logs.selectList(new LambdaQueryWrapper<DevMovementLog>().eq(DevMovementLog::getItemNo, itemNo.trim())
                        .orderByDesc(DevMovementLog::getId).last("limit 500"))
                .stream().map(e -> new DeviceMovement(e.getId(), e.getItemType(), e.getItemNo(), e.getEvent(), e.getFromRef(),
                        e.getToRef(), e.getAgentBefore(), e.getAgentAfter(), e.getReason(), e.getSourceRef(), e.getOperatorNo(),
                        e.getOccurredAt())).toList();
    }

    /**
     * 进出仓的履历（本地引擎的库存流水，S4）：from 或 to 是仓（WH:），新的在前。
     * warehouseNo 为空 = 所有仓；itemType / itemNo 可空。
     */
    public ai.neargo.common.core.PageResult<DeviceMovement> warehouseMoves(String warehouseNo, String itemType, String itemNo,
                                                                         int page, int size, List<String> visibleWarehouses) {
        LambdaQueryWrapper<DevMovementLog> w = new LambdaQueryWrapper<DevMovementLog>().orderByDesc(DevMovementLog::getId);
        warehouseScope(w, visibleWarehouses);
        if (warehouseNo != null && !warehouseNo.isBlank()) {
            String ref = wh(warehouseNo.trim());
            w.and(x -> x.eq(DevMovementLog::getFromRef, ref).or().eq(DevMovementLog::getToRef, ref));
        } else {
            w.and(x -> x.likeRight(DevMovementLog::getFromRef, "WH:").or().likeRight(DevMovementLog::getToRef, "WH:"));
        }
        if (itemType != null && !itemType.isBlank()) w.eq(DevMovementLog::getItemType, itemType.trim());
        if (itemNo != null && !itemNo.isBlank()) w.like(DevMovementLog::getItemNo, itemNo.trim());
        var p = logs.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(Math.max(1, page),
                Math.min(Math.max(1, size), 200)), w);
        return new ai.neargo.common.core.PageResult<>(p.getRecords().stream().map(e -> new DeviceMovement(e.getId(), e.getItemType(),
                e.getItemNo(), e.getEvent(), e.getFromRef(), e.getToRef(), e.getAgentBefore(), e.getAgentAfter(), e.getReason(),
                e.getSourceRef(), e.getOperatorNo(), e.getOccurredAt())).toList(), p.getTotal());
    }

    /** {@code since} 之后所有进出仓的履历，时间升序（本地引擎的期间报表用；上限 20000 行）。 */
    public List<DeviceMovement> warehouseMovesSince(LocalDateTime since, List<String> visibleWarehouses) {
        LambdaQueryWrapper<DevMovementLog> w = new LambdaQueryWrapper<DevMovementLog>().ge(DevMovementLog::getOccurredAt, since)
                        .and(x -> x.likeRight(DevMovementLog::getFromRef, "WH:").or().likeRight(DevMovementLog::getToRef, "WH:"))
                        .orderByAsc(DevMovementLog::getId).last("limit 20000");
        warehouseScope(w, visibleWarehouses);
        return logs.selectList(w)
                .stream().map(e -> new DeviceMovement(e.getId(), e.getItemType(), e.getItemNo(), e.getEvent(), e.getFromRef(),
                        e.getToRef(), e.getAgentBefore(), e.getAgentAfter(), e.getReason(), e.getSourceRef(), e.getOperatorNo(),
                        e.getOccurredAt())).toList();
    }

    private static void warehouseScope(LambdaQueryWrapper<DevMovementLog> w, List<String> warehouses) {
        if (warehouses == null) return;
        if (warehouses.isEmpty()) { w.apply("1=0"); return; }
        List<String> refs = warehouses.stream().map(MovementRecorder::wh).toList();
        w.and(q -> q.in(DevMovementLog::getFromRef, refs).or().in(DevMovementLog::getToRef, refs));
    }

    public static String wh(String no) {
        return no == null ? null : "WH:" + no;
    }

    public static String agent(String no) {
        return no == null ? null : "AGENT:" + no;
    }

    public static String site(String no) {
        return no == null ? null : "SITE:" + no;
    }

    public static String loc(String no) {
        return no == null ? null : "LOC:" + no;
    }

    /** 出库池里的位置：到站点的记站点，只到商家的记商家。 */
    public static String pool(String agentNo, String siteNo) {
        return siteNo != null ? site(siteNo) : agent(agentNo);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
