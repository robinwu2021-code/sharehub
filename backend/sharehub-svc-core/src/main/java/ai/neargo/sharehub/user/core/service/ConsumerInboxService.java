package ai.neargo.sharehub.user.core.service;

/**
 * 站内信的**写入口** —— 此前不存在。
 *
 * <h3>这张表一条消息都没有过</h3>
 * {@code usr_message} 有表、有实体、有 mapper、有读服务（分页 + 标已读 + 属主鉴权），
 * C 端有两个端点、有页面、有未读角标、点开即标已读 —— 整条读侧一应俱全，
 * 唯独<b>全仓零处 insert</b>。收件箱因此永远是「暂无消息」，
 * 而页面、接口、空态文案都工作得很好，看起来就像这个用户没收到过消息。
 *
 * <h3>为什么 INAPP 尤其不该缺</h3>
 * 通知系统四个渠道（INAPP / PUSH / SMS / SUBSCRIBE）里，
 * <b>INAPP 是唯一不需要渠道商的那个</b> —— 写一行记录就完事 ——
 * 却和短信一样被「渠道商对接留待接入层」的 TODO 挡在外面。
 * 而 {@code NotifySendServiceImpl.send} 对任何渠道都落 {@code status=SENT}，
 * 所以运营端发送记录里那条 INAPP 显示「已发送」，用户的收件箱是空的：
 * 两边各自看起来都正常。
 *
 * <h3>不经 NotifyPort 的理由</h3>
 * {@code NotifyPort} 的三个方法都是「投递给一个 target」，而站内信没有 target ——
 * 它是往自己库里写一行。硬塞进 {@code sendToTarget} 会让 {@code notify_log}
 * 多出一堆没有投递对象的记录，而那张表的语义是「触达流水（含渠道成本）」。
 * 两者的正确关系是：将来接 INAPP 模板时由通知侧**调用本服务**，而不是反过来。
 */
public interface ConsumerInboxService {

    /**
     * 投一条消息进某人的收件箱。
     *
     * <p><b>三语都要传</b>（[db-design §1.5]）—— 后端挑语言要先知道「这个用户此刻把界面
     * 切到了哪一种」，而那只有端上知道，所以三语全量下发、由端取。
     * 传单语的后果是阿语界面上出现一句中文，且不报错。
     *
     * @param type ORDER / WALLET / COUPON / SYSTEM / ACTIVITY / CS（{@code usr_message.type} 词表）
     * @return 消息号；{@code cUserNo} 为空时不写并返回 {@code null}
     *         —— <b>无主的消息不该落库</b>，它谁也看不到，只会让「消息总数」这个口径虚高
     */
    String post(String cUserNo, String type, Text title, Text body);

    /** 三语文本。 */
    record Text(String zh, String en, String ar) {
    }
}
