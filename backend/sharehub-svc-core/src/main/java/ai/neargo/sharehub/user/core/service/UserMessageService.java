package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.MessageItem;

/** 站内消息中心（usr_message，C-MS-03）。全部方法<b>按属主过滤</b>，只见自己（IDOR 防护）。 */
public interface UserMessageService {

    /**
     * 我的消息分页。
     *
     * @param type 分类筛选（ORDER/WALLET/…），空则全部
     * @param read 只看已读/未读；null 则全部
     */
    PageResult<MessageItem> pageByOwner(String cUserNo, Integer page, Integer size,
                                        String type, Boolean read);

    /**
     * 标记已读（幂等：已读再调不改 {@code readAt}，保留首次已读时间）。
     *
     * @throws org.springframework.security.access.AccessDeniedException 消息不属于该用户
     */
    MessageItem markRead(String cUserNo, String messageNo);
}
