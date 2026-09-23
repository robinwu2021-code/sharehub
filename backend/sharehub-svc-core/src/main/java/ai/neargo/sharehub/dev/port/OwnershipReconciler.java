package ai.neargo.sharehub.dev.port;

import ai.neargo.sharehub.api.platform.event.AssetAssignedEvent;
import ai.neargo.sharehub.common.event.OutboxStatus;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import ai.neargo.sharehub.common.event.mapper.SysOutboxMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 划拨归属的对账补偿（ADR-019 决策二第 ③ 段）。
 *
 * <p><b>为什么最终一致必须配补偿</b>：事件可能投递失败、消费方可能崩在半路。
 * 只有「发事件」没有「对账」的最终一致，实际上是「大概率一致」——
 * 一旦不一致，代理商看不到自己的机柜，而系统里没有任何地方会发现这件事。
 *
 * <p>本类做两件事：
 * <ol>
 *   <li><b>重投未决事件</b>：把 {@code PENDING}/{@code FAILED} 且已到重试时间的 outbox 行捞出来重发；</li>
 *   <li><b>比对归属</b>：点位的 {@code agent_no} 与其下机柜的 {@code agent_no} 不一致即修复并告警。</li>
 * </ol>
 *
 * <p><b>当前只实现第 1 件</b>：第 2 件需要跨表比对 SQL（点位 ⋈ 机柜），
 * 而这正是 G1 卡口禁止的跨服务 JOIN —— 拆分后它应由 core 侧自行比对
 * （core 同时持有 {@code dev_cabinet} 与事件里带来的点位归属快照）。
 * 在订单拆表（M1）落地、读模型建立后一并补齐，届时有现成的宽表可比。
 *
 * <p>调度未接：单体期由运维手动或后续接 {@code @Scheduled}。
 * **不在此处加 {@code @Scheduled}** —— 定时任务在多副本下会并发跑，
 * 需要分布式锁，那是与 outbox 投递器一起设计的事。
 */
@Component
public class OwnershipReconciler {

    private static final Logger log = LoggerFactory.getLogger(OwnershipReconciler.class);
    private static final int BATCH = 100;
    private static final int MAX_RETRY = 10;

    private final SysOutboxMapper outbox;
    private final AssetAssignedListener listener;

    public OwnershipReconciler(SysOutboxMapper outbox, AssetAssignedListener listener) {
        this.outbox = outbox;
        this.listener = listener;
    }

    /**
     * 重投未决的划拨事件。
     *
     * @return 本轮重投成功的条数
     */
    public int redeliverPending() {
        List<SysOutbox> rows = outbox.selectList(new LambdaQueryWrapper<SysOutbox>()
                .eq(SysOutbox::getEventType, AssetAssignedEvent.TYPE)
                .in(SysOutbox::getStatus, List.of(OutboxStatus.PENDING.name(), OutboxStatus.FAILED.name()))
                .lt(SysOutbox::getRetryCount, MAX_RETRY)
                .and(w -> w.isNull(SysOutbox::getNextRetryAt)
                        .or().le(SysOutbox::getNextRetryAt, LocalDateTime.now()))
                .orderByAsc(SysOutbox::getId)
                .last("limit " + BATCH));

        int ok = 0;
        for (SysOutbox row : rows) {
            try {
                var e = ai.neargo.sharehub.common.Json.read(row.getPayload(),
                        ai.neargo.sharehub.api.platform.event.AssetAssignedEvent.class);
                if (e == null) {
                    throw new IllegalStateException("载荷无法反序列化");
                }
                DataScopeContext.executeWithoutScope(() -> {
                    listener.on(e);
                    return null;
                });
                SysOutbox upd = new SysOutbox();
                upd.setId(row.getId());
                upd.setStatus(OutboxStatus.SENT.name());
                upd.setSentAt(LocalDateTime.now());
                outbox.updateById(upd);
                ok++;
            } catch (Exception ex) {
                // 退避重试：失败次数越多等得越久，避免坏事件把轮询打满
                SysOutbox upd = new SysOutbox();
                upd.setId(row.getId());
                upd.setStatus(OutboxStatus.FAILED.name());
                upd.setRetryCount((row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1);
                upd.setLastError(String.valueOf(ex.getMessage()));
                upd.setNextRetryAt(LocalDateTime.now().plusMinutes(
                        Math.min(60, 1L << Math.min(6, row.getRetryCount() == null ? 0 : row.getRetryCount()))));
                upd.setLastError(String.valueOf(ex.getMessage()));
                outbox.updateById(upd);
                log.warn("划拨事件重投失败：eventNo={} retry={}", row.getEventNo(), upd.getRetryCount(), ex);
            }
        }
        if (!rows.isEmpty()) {
            log.info("划拨事件重投：捞出 {} 条，成功 {} 条", rows.size(), ok);
        }
        return ok;
    }
}
