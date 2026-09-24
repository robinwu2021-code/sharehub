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
    Object send(String pushNo, String idempotencyKey, String operatorName);

    /** 排期：DRAFT → SCHEDULED。{@code scheduledAt} 为 ISO 时刻。 */
    Object schedule(String pushNo, String scheduledAt, String operatorName);

    /** 收尾：SENDING → SENT，落触达统计。SENT 是终态，不可重发。 */
    Object finish(String pushNo, Integer targetCount, Integer successCount);

    /**
     * 扫描到点的排期推送并发出去，返回处理条数。
     *
     * <p>**将来挂成共用调度器的 JobHandler**（v4/07）；在那之前由运营端手动端点触发。
     * 两者调的是同一个方法，接线时业务代码不用改 —— 这也是本方法自身必须幂等的原因。
     */
    int sweepDue(String now);
}
