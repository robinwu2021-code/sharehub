package ai.neargo.sharehub.trade.job;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.common.job.JobDeclaration;
import ai.neargo.sharehub.common.job.JobHandler;
import ai.neargo.sharehub.common.job.JobInvocation;
import ai.neargo.sharehub.common.job.JobResult;
import ai.neargo.sharehub.trade.OrderStatus;
import ai.neargo.sharehub.trade.entity.OrdOrder;
import ai.neargo.sharehub.trade.mapper.OrdMapper;
import ai.neargo.sharehub.trade.service.RentOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 逾期达封顶转买断（联动 E17）。每小时扫进行中订单：按当前时长试算触及总封顶（买断价）→ 买断结单、宝 → SOLD、发结算事件。
 *
 * <p>没有它，逾期未还的订单会永远「进行中」：宝永远在借，用户的应付永远在涨（此前结算时总封顶也没生效），
 * 分润永远不产生。撤场时拆机当天仍未还的订单也靠它收尾（裁决 #3）。
 */
@Component
public class OrderOverdueBuyoutJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderOverdueBuyoutJob.class);
    private static final int BATCH = 500;

    private final OrdMapper orders;
    private final RentOrderService rents;

    public OrderOverdueBuyoutJob(OrdMapper orders, RentOrderService rents) {
        this.orders = orders;
        this.rents = rents;
    }

    @Override
    public String name() {
        return "order-overdue-buyout";
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int bought = 0;
        long afterId = 0;
        // 一小时内借出的不可能触顶，跳过以减少试算
        LocalDateTime startedBefore = LocalDateTime.now().minusHours(1);
        while (true) {
            final long from = afterId;
            List<OrdOrder> batch = DataScopeContext.executeWithoutScope(() -> orders.selectList(new LambdaQueryWrapper<OrdOrder>()
                    .select(OrdOrder::getId, OrdOrder::getOrderNo).eq(OrdOrder::getStatus, OrderStatus.IN_USE.name())
                    .lt(OrdOrder::getStartedAt, startedBefore).gt(OrdOrder::getId, from)
                    .orderByAsc(OrdOrder::getId).last("limit " + BATCH)));
            if (batch.isEmpty()) break;
            afterId = batch.get(batch.size() - 1).getId();
            for (OrdOrder o : batch) {
                try {
                    if (rents.buyoutIfCapped(o.getOrderNo())) bought++;
                } catch (RuntimeException e) {
                    // 单笔失败不拖垮整批；下小时重试。持续失败的那一笔需要人看（快照损坏 / 计价方案缺项）
                    log.error("逾期买断失败 orderNo={}，下轮重试", o.getOrderNo(), e);
                }
            }
            if (batch.size() < BATCH) break;
        }
        return bought == 0 ? JobResult.skipped("无触顶订单") : JobResult.success("bought=" + bought);
    }

    @Configuration
    static class Declarations {
        @Bean
        JobDeclaration orderOverdueBuyoutDeclaration() {
            return JobDeclaration.of("order-overdue-buyout", "逾期达封顶转买断", "0 15 * * * *")
                    .ownerModule("trade").timeoutSec(300).lockAtMostSec(900).build();
        }
    }
}
