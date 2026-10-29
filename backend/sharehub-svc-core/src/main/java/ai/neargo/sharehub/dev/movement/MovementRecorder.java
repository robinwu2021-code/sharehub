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
        String op = SecurityUtils.userNo();
        e.setOperatorNo(op == null ? "SYSTEM" : op);
        e.setOccurredAt(LocalDateTime.now());
        logs.insert(e);
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
