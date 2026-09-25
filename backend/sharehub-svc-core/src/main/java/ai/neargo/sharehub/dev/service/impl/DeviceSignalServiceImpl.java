package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.common.event.dedup.EventIdempotency;
import ai.neargo.sharehub.dev.OnlineStatus;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.IngestResult;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevEventCode;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.EventCodeMapper;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import ai.neargo.sharehub.dev.service.ProtectionService;
import ai.neargo.sharehub.dev.service.TrialRentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 设备信号入口实现。
 *
 * <p>处理顺序：状态类信号回写机柜 → 恢复信号解除对应保护 → 按字典执行保护动作 → 试借还推进 → 发 {@link DeviceSignalEvent}
 * （业务告警订阅）。止损在设备域内同步生效，不依赖事件投递。
 *
 * <p><b>离线不是设备上报的信号</b>：由业务告警的定时判定按心跳时间推断，并以告警身份申请整柜停借。
 */
@Service
public class DeviceSignalServiceImpl implements DeviceSignalService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSignalServiceImpl.class);
    private static final String HANDLER = "DeviceSignalService";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter RETRY_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final EventCodeMapper codes;
    private final CabinetMapper cabinets;
    private final ProtectionService protections;
    private final TrialRentService trials;
    private final ai.neargo.sharehub.dev.port.PowerbankTracker powerbankTracker;
    private final CabinetService commands;
    private final EventIdempotency idempotency;
    private final DomainEventBus events;
    private final TransactionTemplate perEvent;
    private final ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.SignalLogMapper signalLog;
    /** 心跳间隔超过它 = 中间掉过一次线（与在线口径同一个窗口）。 */
    private static final int LINK_GAP_MIN = 3;

    public DeviceSignalServiceImpl(EventCodeMapper codes, CabinetMapper cabinets, ProtectionService protections,
                                   TrialRentService trials, CabinetService commands, EventIdempotency idempotency,
                                   DomainEventBus events, PlatformTransactionManager tm,
                                   ai.neargo.sharehub.dev.port.PowerbankTracker powerbankTracker,
                                   ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.SignalLogMapper signalLog) {
        this.signalLog = signalLog;
        this.powerbankTracker = powerbankTracker;
        this.codes = codes;
        this.cabinets = cabinets;
        this.protections = protections;
        this.trials = trials;
        this.commands = commands;
        this.idempotency = idempotency;
        this.events = events;
        this.perEvent = new TransactionTemplate(tm);
        this.perEvent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public IngestResult ingest(DeviceEventBatch batch) {
        if (batch == null || batch.events() == null || batch.events().isEmpty()) return new IngestResult(0, 0, 0);
        if (batch.events().size() > 500) throw ai.neargo.sharehub.common.BizException.badRequest("error.signal.batch_too_large", 500);
        int accepted = 0, dup = 0, unknown = 0;
        for (DeviceEvent e : batch.events()) {
            if (e.eventId() == null || e.eventId().isBlank() || e.type() == null || e.deviceNo() == null) {
                throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "eventId / type / deviceNo");
            }
            if (e.eventId().length() > 36) throw ai.neargo.sharehub.common.BizException.badRequest("error.signal.event_id_too_long", e.eventId());
            int[] outcome = new int[1];   // 0 已处理 · 1 重复 · 2 未知码
            try {
                perEvent.executeWithoutResult(st -> DataScopeContext.executeWithoutScope(() -> {
                    boolean ran = idempotency.once(e.eventId(), HANDLER, e.type(), () -> outcome[0] = handle(e));
                    if (!ran) outcome[0] = 1;
                    return null;
                }));
            } catch (RuntimeException ex) {
                // 单条失败不拖垮整批；去重记录随事务回滚，网关重推时会再处理
                log.error("设备事件处理失败 eventId={} type={} deviceNo={}，等待网关重推", e.eventId(), e.type(), e.deviceNo(), ex);
                continue;
            }
            switch (outcome[0]) {
                case 1 -> dup++;
                case 2 -> unknown++;
                default -> accepted++;
            }
        }
        return new IngestResult(accepted, dup, unknown);
    }

    @Override
    public java.util.List<ai.neargo.sharehub.dev.dto.DeviceOpsDtos.SignalCode> codes() {
        return codes.selectList(new LambdaQueryWrapper<DevEventCode>().orderByAsc(DevEventCode::getCategory).orderByAsc(DevEventCode::getId))
                .stream().map(c -> new ai.neargo.sharehub.dev.dto.DeviceOpsDtos.SignalCode(c.getCode(), c.getName(), c.getNameEn(),
                        c.getCategory(), c.getScope(), c.getProtectiveAction(), c.getClearsCode(), c.getFeeds()))
                .toList();
    }

    /** @return 0 已处理 / 2 未知码或未知设备（记 WARN，不报错） */
    private int handle(DeviceEvent e) {
        DevEventCode code = codes.selectOne(new LambdaQueryWrapper<DevEventCode>().eq(DevEventCode::getCode, e.type()).last("limit 1"));
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, e.deviceNo()).last("limit 1"));
        if (c == null) {
            log.warn("设备事件指向未知设备 deviceNo={} eventId={} vendor={} —— 设备未建档，需补档后网关重推", e.deviceNo(), e.eventId(), e.vendorCode());
            return 2;
        }
        LocalDateTime at = e.occurredAt() == null ? LocalDateTime.now() : e.occurredAt();
        if (code == null) {
            // 未映射的厂商码：按无保护动作处理，但要能补映射 —— WARN 里带厂商与原始码
            log.warn("未知设备信号码 type={} vendor={} vendorErrorCode={} deviceNo={} —— 需在 dev_event_code 补映射",
                    e.type(), e.vendorCode(), e.vendorErrorCode(), e.deviceNo());
            return 2;
        }
        if ("HEARTBEAT".equals(code.getCode())) {
            // 掉线计数（E1）：设备不会报「我掉线了」，只能在它回来时看出来 —— 距上次心跳超过窗口 = 一次掉线后恢复
            LocalDateTime prev = parseTs(c.getLastHeartbeatAt());
            if (prev != null && at.isAfter(prev.plusMinutes(LINK_GAP_MIN))) logSignal(c.getCabinetNo(), "LINK_RESTORED", null, null, at);
            LambdaUpdateWrapper<DevCabinet> u = new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, c.getCabinetNo())
                    .set(DevCabinet::getOnlineStatus, OnlineStatus.ONLINE.name()).set(DevCabinet::getLastHeartbeatAt, at.format(TS));
            cabinets.update(null, u);
        }
        if (code.getClearsCode() != null) {
            protections.releaseBySignal(code.getClearsCode(), c.getCabinetNo(), e.slotIndex(), "恢复信号 " + code.getCode());
        }
        DeviceSignalEvent s = new DeviceSignalEvent(e.eventId(), code.getCode(), c.getCabinetNo(), e.slotIndex(), e.powerbankNo(),
                c.getSiteNo(), c.getAgentNo(), e.vendorCode(), e.vendorErrorCode(), at.toString(), e.attrs() == null ? Map.of() : e.attrs());
        String action = code.getProtectiveAction();
        if ("AUTO_EJECT_RETRY".equals(action)) {
            autoEjectRetry(s, at);
        } else if (action != null && !"NONE".equals(action)) {
            protections.applyBySignal(s, ProtectionAction.of(action).name());
        }
        trials.onSignal(s);
        // 仓位识别到宝：宝回柜（借出中 → 在柜，丢失 → 找回）。订单若还在进行中，由业务告警 RETURN_NOT_RECOGNIZED 按此刻结单
        if ("RETURN_SN_SEEN".equals(code.getCode()) && e.powerbankNo() != null) {
            powerbankTracker.returned(e.powerbankNo(), c.getCabinetNo(), e.slotIndex());
        }
        if (!"HEARTBEAT".equals(code.getCode())) {
            logSignal(c.getCabinetNo(), code.getCode(), e.slotIndex(), e.powerbankNo(), at);   // 窗口计数的输入（心跳量大，不入）
            events.publish(s);   // 心跳量大且只改状态，不进 Outbox
        }
        return 0;
    }

    private void logSignal(String cabinetNo, String code, Integer slot, String powerbankNo, LocalDateTime at) {
        ai.neargo.sharehub.dev.entity.DevSignalLog l = new ai.neargo.sharehub.dev.entity.DevSignalLog();
        l.setCabinetNo(cabinetNo);
        l.setCode(code);
        l.setSlotIndex(slot);
        l.setPowerbankNo(powerbankNo);
        l.setOccurredAt(at);
        signalLog.insert(l);
    }

    private static LocalDateTime parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException ex) {
            return null;   // 历史脏格式：不计这一次，宁可少计一次掉线也不在心跳路径上报错
        }
    }

    /**
     * 卡宝：先自动重弹一次；同一仓 10 分钟内再报卡宝 → 仓位禁用（等恢复信号 SLOT_EJECT_OK 解除）。
     * 「是不是第二次」借去重表判：同一 (柜, 仓, 10 分钟桶) 只放行一次重弹。
     */
    private void autoEjectRetry(DeviceSignalEvent s, LocalDateTime at) {
        if (s.slotIndex() == null) return;
        String bucket = at.withMinute(at.getMinute() / 10 * 10).format(RETRY_BUCKET);
        // 去重键 = (10 分钟桶, 消费者)；柜与仓放进消费者标识 —— event_no 只有 36 位，放不下完整键
        boolean first = idempotency.once("STUCK" + bucket, HANDLER + ".retry:" + s.cabinetNo() + ":" + s.slotIndex(), "SLOT_STUCK",
                () -> commands.sendCommand(s.cabinetNo(), "EJECT", Map.of("slotIndex", s.slotIndex(), "reason", "AUTO_EJECT_RETRY")));
        if (!first) {
            protections.applyBySignal(s, ProtectionAction.SLOT_DISABLE.name());
            log.warn("仓位卡宝重弹后仍失败，已禁用 cabinetNo={} slot={} —— 需运维现场处理", s.cabinetNo(), s.slotIndex());
        }
    }
}
