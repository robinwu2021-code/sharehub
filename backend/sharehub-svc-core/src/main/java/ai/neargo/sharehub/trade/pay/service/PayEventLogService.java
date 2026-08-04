package ai.neargo.sharehub.trade.pay.service;

import ai.neargo.sharehub.trade.pay.entity.PayEventLog;

import java.util.List;

/**
 * nearpay 回调事件留痕（pay_event_log）—— **回调幂等的唯一落点**（[db-design §1.6]）。
 *
 * <p>append 表：只插不改（{@link #markProcessed} 只翻 {@code processed} 标记），没有 update/delete 语义。
 *
 * <p><b>回调处理器的标准写法</b>：
 * <pre>{@code
 * if (payEventLogService.alreadyProcessed(refNo, eventType)) return ok(); // 重放，直接放行
 * payEventLogService.record(refNo, eventType, rawJson);                   // 先留痕（UK 兜底并发）
 * ... 业务处理（改 pay_order / pay_auth / 驱动订单）...
 * payEventLogService.markProcessed(refNo, eventType);
 * }</pre>
 * 先留痕后处理：反过来做，进程在处理与留痕之间挂掉，重放时就会二次改资金。
 */
public interface PayEventLogService {

    /**
     * 该事件是否已被处理过（UK({@code ref_no}, {@code event_type}) 命中且 {@code processed=1}）。
     * 回调处理器进来第一件事就调它。
     */
    boolean alreadyProcessed(String refNo, String eventType);

    /** 是否收到过该事件（不论是否处理完）。并发下靠表上的 UK 兜底，本方法只做快路径。 */
    boolean received(String refNo, String eventType);

    /**
     * 落一条事件留痕。重复（UK 冲突）时不抛，返回 {@code false} 表示「这是重放」。
     *
     * @param raw 原始报文 JSON 原文
     */
    boolean record(String refNo, String eventType, String raw);

    /** 业务处理完成后翻标记。返回是否命中。 */
    boolean markProcessed(String refNo, String eventType);

    /** 按对侧单号查全部事件（排障 / 对账时间线）。 */
    List<PayEventLog> byRef(String refNo);
}
