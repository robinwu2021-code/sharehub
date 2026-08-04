package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.MessageItem;
import ai.neargo.sharehub.user.core.entity.UsrMessage;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrMessageMapper;
import ai.neargo.sharehub.user.core.service.UserMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 消息中心实现。
 *
 * <p>属主号<b>由调用方从 {@code ConsumerContext} 取</b>再传进来，service 不碰 SecurityContext ——
 * 但每个查询都强制拼 {@code c_user_no = ?}，即便控制器写错也查不到别人的消息。
 */
@Service
public class UserMessageServiceImpl implements UserMessageService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UsrMessageMapper mapper;

    public UserMessageServiceImpl(UsrMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<MessageItem> pageByOwner(String cUserNo, Integer page, Integer size,
                                               String type, Boolean read) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<UsrMessage> w = new LambdaQueryWrapper<UsrMessage>()
                .eq(UsrMessage::getCUserNo, cUserNo);
        if (type != null && !type.isBlank()) w.eq(UsrMessage::getType, type);
        if (read != null) w.eq(UsrMessage::getIsRead, read ? 1 : 0);
        w.orderByDesc(UsrMessage::getId);

        Page<UsrMessage> r = mapper.selectPage(new Page<>(p, s), w);
        List<MessageItem> rows = r.getRecords().stream().map(UserMessageServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public MessageItem markRead(String cUserNo, String messageNo) {
        UsrMessage e = mapper.selectOne(new LambdaQueryWrapper<UsrMessage>()
                .eq(UsrMessage::getMessageNo, messageNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("消息不存在: " + messageNo);
        if (!cUserNo.equals(e.getCUserNo())) {
            throw new AccessDeniedException("无权访问他人消息"); // 不泄露「存在但不属于你」之外的信息
        }
        if (!Integer.valueOf(1).equals(e.getIsRead())) { // 幂等：已读不覆盖首次已读时间
            e.setIsRead(1);
            e.setReadAt(LocalDateTime.now().format(TS));
            mapper.updateById(e);
        }
        return toVO(e);
    }

    private static MessageItem toVO(UsrMessage e) {
        return new MessageItem(e.getMessageNo(), e.getType(), e.getTitle(), e.getBody(),
                Integer.valueOf(1).equals(e.getIsRead()), e.getReadAt(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
