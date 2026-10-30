package ai.neargo.sharehub.inv.remote;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 拉取库存事件（S3.2）：至少一次投递 → 进 inventory_inbox（event_id 唯一）去重 → 投影 → 确认游标。
 *
 * <p>本系统发起的动作，本地投影在发起时已同步写好；这里处理的是<b>库存服务侧发生、本系统不知道</b>的变化
 * （在进销存界面直接重定向 / 撤回出库池记录），把本地出库池归属拉齐。其余事件只入箱留痕。
 * 单条处理失败标 FAILED 不阻塞游标（留给人看）；游标过期（410）则从库存服务记的确认位置重拉。
 */
@Component
public class InventoryEventPuller {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventPuller.class);
    static final String CURSOR = "events:seq";
    private static final int PAGE = 100;

    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final InventoryInboxMapper inbox;
    private final CabinetMapper cabinets;
    private final MovementRecorder movements;
    private final SysParamPort params;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate tx;

    public InventoryEventPuller(RemoteInventoryPort remote, InventoryLinks links, InventoryInboxMapper inbox, CabinetMapper cabinets,
                                MovementRecorder movements, SysParamPort params, TransactionTemplate tx) {
        this.remote = remote;
        this.links = links;
        this.inbox = inbox;
        this.cabinets = cabinets;
        this.movements = movements;
        this.params = params;
        this.tx = tx;
    }

    public record Result(int pulled, int processed, int duplicates, int failed) {
    }

    /** 拉到没有新事件为止（每轮最多 20 页，防止一次跑太久）。引擎不是 SERVICE 或未配置时跳过。 */
    public Result pullOnce() {
        if (!ai.neargo.sharehub.inv.port.InventoryEngineRouter.isService(params) || !remote.api().configured()) {
            return new Result(0, 0, 0, 0);
        }
        int pulled = 0, processed = 0, dup = 0, failed = 0;
        for (int page = 0; page < 20; page++) {
            String cur = links.get(CURSOR);
            InventoryApi.EventPage p;
            try {
                p = remote.api().pullEvents(cur == null || cur.isBlank() ? null : Long.parseLong(cur), PAGE);
            } catch (InventoryRemoteException e) {
                if (e.status != 410) throw e;
                // 游标过期：从库存服务记的确认位置重拉（至少一次 + 收件箱去重，不会重复处理）
                log.warn("库存事件游标已过期 cursor={}，改从服务端确认位置重拉", cur);
                p = remote.api().pullEvents(null, PAGE);
            }
            if (p.events().isEmpty()) break;
            for (InventoryApi.Event e : p.events()) {
                pulled++;
                switch (handle(e)) {
                    case "DONE" -> processed++;
                    case "DUP" -> dup++;
                    default -> failed++;
                }
            }
            links.put(CURSOR, String.valueOf(p.lastSeq()));
            remote.api().ackEvents(p.lastSeq());
            if (p.events().size() < PAGE) break;
        }
        if (failed > 0) log.warn("库存事件处理失败 {} 条：见 inventory_inbox status=FAILED，按 error 列逐条处理", failed);
        return new Result(pulled, processed, dup, failed);
    }

    private String handle(InventoryApi.Event e) {
        InventoryInbox row = new InventoryInbox();
        row.setEventId(e.eventId());
        row.setSeq(e.seq());
        row.setType(e.type());
        row.setResourceId(e.resourceId());
        row.setResourceVersion(e.resourceVersion());
        row.setPayload(e.payload());
        // 先按「已处理」入箱、同事务投影：投影失败整体回滚，再单独落一行 FAILED
        row.setStatus(InboxStatus.DONE.name());
        row.setProcessedAt(LocalDateTime.now());
        try {
            return tx.execute(st -> {
                try {
                    inbox.insert(row);
                } catch (DuplicateKeyException d) {
                    return "DUP";
                }
                DataScopeContext.executeWithoutScope(() -> {
                    project(e);
                    return null;
                });
                return "DONE";
            });
        } catch (RuntimeException ex) {
            // 投影失败：单独落一行 FAILED（上面的事务已回滚），游标照走 —— 不让一条坏事件卡住后面所有事件
            log.error("库存事件投影失败 eventId={} type={} resourceId={}：已记 inventory_inbox FAILED，需人工核对本地出库池",
                    e.eventId(), e.type(), e.resourceId(), ex);
            try {
                row.setId(null);
                row.setStatus(InboxStatus.FAILED.name());
                row.setProcessedAt(null);
                row.setError(String.valueOf(ex.getMessage()).substring(0, Math.min(500, String.valueOf(ex.getMessage()).length())));
                inbox.insert(row);
            } catch (DuplicateKeyException ignored) {
                return "DUP";
            }
            return "FAILED";
        }
    }

    /** 库存服务侧的出库池变化拉齐到本地（只动未绑定的出库池设备；已绑定的不跟 —— 绑定是业务侧的事）。 */
    private void project(InventoryApi.Event e) {
        if (!"DispatchRedirected".equals(e.type()) && !"DispatchRevoked".equals(e.type())) return;
        JsonNode p = parse(e.payload());
        if (!p.path("parentDispatchId").isNull() && !p.path("parentDispatchId").isMissingNode()) return;
        String serial = p.path("serialNo").asText(null);
        if (serial == null) return;
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, serial).last("limit 1"));
        if (c == null || c.getIssuedAgentNo() == null || c.getLocationNo() != null || !CabinetStatus.IN_STOCK.name().equals(c.getStatus())) return;
        if ("DispatchRedirected".equals(e.type())) {
            String agent = p.path("rootTargetRef").asText(null);
            if (agent == null || Objects.equals(agent, c.getIssuedAgentNo())) return;
            cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, serial).isNull(DevCabinet::getLocationNo)
                    .set(DevCabinet::getIssuedAgentNo, agent).set(DevCabinet::getIssuedSiteNo, null));
            movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, serial, MovementEvent.REDIRECT,
                    MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo()), MovementRecorder.agent(agent),
                    c.getIssuedAgentNo(), agent, "库存服务侧重定向", e.resourceId()));
        } else {
            // 撤回：库存服务已冲回原仓；本地离开出库池，落到默认回仓仓（与库存服务不一致时以盘点纠正）
            cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, serial).isNull(DevCabinet::getLocationNo)
                    .set(DevCabinet::getIssuedAgentNo, null).set(DevCabinet::getIssuedAt, null).set(DevCabinet::getIssuedBy, null)
                    .set(DevCabinet::getIssuedSiteNo, null).set(DevCabinet::getIssueReceiverNo, null).set(DevCabinet::getIssueClaimedAt, null)
                    .set(DevCabinet::getWarehouseNo, params.textOf("inv.return_warehouse", "WH001")));
            movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, serial, MovementEvent.POOL_RETURN,
                    MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo()), MovementRecorder.wh(params.textOf("inv.return_warehouse", "WH001")),
                    c.getIssuedAgentNo(), null, "库存服务侧撤回", e.resourceId()));
        }
    }

    private JsonNode parse(String payload) {
        try {
            return payload == null ? JSON.createObjectNode() : JSON.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("inventory event payload is not JSON: " + payload, ex);
        }
    }
}
