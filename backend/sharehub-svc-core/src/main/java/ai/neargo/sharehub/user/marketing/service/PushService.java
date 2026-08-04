package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.PushMessageVO;
import ai.neargo.sharehub.user.marketing.entity.MktPush;

/**
 * 推送触达（mkt_push）。建单/改单是配置类 → 继承通用 CRUD。
 *
 * <p><b>下发动作不在这里</b>：真正的发送要经渠道适配 + 触达拉黑（{@code notify_blacklist}）
 * + 静默时段过滤，并逐条落 {@code notify_log}，那属于 platform 域的触达能力。
 * 本服务只负责「发什么、发给谁（audience JSON）、发了多少（sentCount）」的账。
 */
public interface PushService extends CrudService<MktPush, PushMessageVO> {

    /** 发送推送。**必带幂等键** —— 推送是真推到用户手机上，双击不该推两次。 */
    Object send(String pushNo, String idempotencyKey);
}
