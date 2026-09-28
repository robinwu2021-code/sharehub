package ai.neargo.sharehub.cs.port;

import ai.neargo.sharehub.api.platform.flow.TicketOutcomeHandler;
import ai.neargo.sharehub.cs.entity.CsTicket;
import ai.neargo.sharehub.cs.mapper.CsTicketMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报障单的 OpsKit 工单回填（M5-3）。只在 {@code wo_no} 还空着时写 —— 重复回调、或本地模式下已有 wo 号时都不动。
 */
@Component
public class CsTicketOutcome implements TicketOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(CsTicketOutcome.class);

    private final CsTicketMapper mapper;

    public CsTicketOutcome(CsTicketMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String bizType() {
        return OpsKitTicketWorkOrderPort.BIZ_TYPE;
    }

    @Override
    @Transactional
    public void onTicketCreated(String bizNo, String ticketNo, boolean attached) {
        int n = mapper.update(null, new LambdaUpdateWrapper<CsTicket>()
                .eq(CsTicket::getTicketNo, bizNo).isNull(CsTicket::getWoNo)
                .set(CsTicket::getWoNo, ticketNo));
        if (n == 0) log.info("报障单工单号已有值，未覆盖 ticketNo={} opskitTicket={}", bizNo, ticketNo);
        if (attached) log.info("报障单并入 OpsKit 既有未结工单 ticketNo={} → {}", bizNo, ticketNo);
    }

    @Override
    public void onTicketFailed(String bizNo, String error) {
        // 谁该做什么：客服在「报障受理」里对这张单再点一次「转工单」（同一幂等键，OpsKit 恢复后即可建上）
        log.error("报障单送 OpsKit 开工单失败（已转死信）ticketNo={}：{} —— 客服可在报障受理页重新「转工单」", bizNo, error);
    }
}
