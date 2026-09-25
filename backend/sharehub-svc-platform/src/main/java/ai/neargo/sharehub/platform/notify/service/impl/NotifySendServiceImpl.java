package ai.neargo.sharehub.platform.notify.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.platform.notify.NotifyLogStatus;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogVO;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendReq;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendResult;
import ai.neargo.sharehub.platform.notify.entity.NotifyLog;
import ai.neargo.sharehub.platform.notify.service.NotifyBlacklistService;
import ai.neargo.sharehub.platform.notify.service.NotifyLogService;
import ai.neargo.sharehub.platform.notify.service.NotifySendService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 发送实现。**顺序是业务规则的一部分**：先查黑名单，再投递，最后落记录。
 *
 * <p>被拦下时仍然落一条 {@code status=FAILED / failReason=BLACKLISTED} 的记录 ——
 * "没发出去"和"从没尝试过"是两回事：前者要能在发送记录页查到，否则运营侧会误判为渠道故障，
 * 也无法统计退订带来的触达衰减。这条记录 {@code cost=0}（未产生渠道费用）。
 *
 * <p>渠道商对接（Twilio/SES/…）留待接入层实现，本骨架只完成「黑名单 → 记账」这段编排。
 */
@Service
public class NotifySendServiceImpl implements NotifySendService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifyBlacklistService blacklistService;
    private final NotifyLogService logService;

    public NotifySendServiceImpl(NotifyBlacklistService blacklistService, NotifyLogService logService) {
        this.blacklistService = blacklistService;
        this.logService = logService;
    }

    @Override
    public SendResult send(SendReq req) {
        if (req == null || req.target() == null || req.target().isBlank()) {
            throw BizException.badRequest("error.notify.target_required");
        }
        String channel = (req.channel() == null || req.channel().isBlank()) ? "SMS" : req.channel();

        NotifyLog e = new NotifyLog();
        e.setChannel(channel);
        e.setTemplateNo(req.templateNo());
        e.setTarget(req.target());   // append 时统一脱敏，明文不出本方法
        e.setScene(req.scene());
        e.setCurrency(req.currency());
        e.setCreatedAt(LocalDateTime.now());

        // —— 发送前必查黑名单（唯一正门，见 NotifySendService 类注释）——
        if (blacklistService.isBlocked(req.target(), channel)) {
            e.setStatus(NotifyLogStatus.FAILED.name());
            e.setFailReason("BLACKLISTED");
            e.setCost(java.math.BigDecimal.ZERO); // 未投递 → 不计费
            NotifyLogVO vo = logService.append(e);
            return new SendResult(vo.logNo(), NotifyLogStatus.FAILED.name(), true, "BLACKLISTED");
        }

        // TODO(接入层)：调用渠道商投递，按其回执回填 status/failReason/cost。
        e.setStatus(NotifyLogStatus.SENT.name());
        e.setSentAt(LocalDateTime.now().format(TS));
        e.setCost(req.cost());
        NotifyLogVO vo = logService.append(e);
        return new SendResult(vo.logNo(), vo.status(), false, null);
    }
}
