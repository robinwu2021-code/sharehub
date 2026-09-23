package ai.neargo.sharehub.cs.service.impl;

import ai.neargo.sharehub.cs.CsSenderType;
import ai.neargo.sharehub.cs.CsSessionStatus;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.cs.dto.CsDtos.CsMessageVO;
import ai.neargo.sharehub.cs.dto.CsDtos.CsSessionVO;
import ai.neargo.sharehub.cs.entity.CsMessage;
import ai.neargo.sharehub.cs.entity.CsSession;
import ai.neargo.sharehub.cs.mapper.CsMessageMapper;
import ai.neargo.sharehub.cs.mapper.CsSessionMapper;
import ai.neargo.sharehub.cs.service.CsSessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 客服会话实现。消息表是 append，只 INSERT；会话的 lastMessage 冗余同步更新。 */
@Service
public class CsSessionServiceImpl implements CsSessionService {

    private static final String TENANT_MAIN = "MAIN";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** {@code last_message} 列宽 512，超长截断而不是让 INSERT 报错。 */
    private static final int SUMMARY_MAX = 500;

    private final CsSessionMapper mapper;
    private final CsMessageMapper messageMapper;

    public CsSessionServiceImpl(CsSessionMapper mapper, CsMessageMapper messageMapper) {
        this.mapper = mapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public PageResult<CsSessionVO> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<CsSession> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(CsSession::getSessionNo, keyword)
                    .or().like(CsSession::getCUserNo, keyword)
                    .or().like(CsSession::getAgentName, keyword));
        }
        w.eq(status != null && !status.isBlank(), CsSession::getStatus, status);
        w.orderByDesc(CsSession::getUpdatedAt).orderByDesc(CsSession::getId);

        Page<CsSession> r = mapper.selectPage(new Page<>(p, s), w);
        List<CsSessionVO> rows = r.getRecords().stream().map(CsSessionServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public List<CsMessageVO> messages(String sessionNo, Integer limit) {
        int n = (limit == null || limit < 1) ? 200 : Math.min(limit, 500);
        return messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                        .eq(CsMessage::getSessionNo, sessionNo)
                        .orderByAsc(CsMessage::getId) // 对话按时间正序读
                        .last("limit " + n))
                .stream().map(CsSessionServiceImpl::toVO).toList();
    }

    @Override
    @Transactional
    public CsMessageVO reply(String sessionNo, String senderNo, String content, String attach) {
        CsSession session = mapper.selectOne(new LambdaQueryWrapper<CsSession>()
                .eq(CsSession::getSessionNo, sessionNo).last("limit 1"));
        if (session == null) throw new IllegalArgumentException("会话不存在: " + sessionNo);
        if (CsSessionStatus.CLOSED.name().equals(session.getStatus())) {
            throw new IllegalStateException("会话已关闭，不能回复: " + sessionNo);
        }

        CsMessage m = new CsMessage();
        m.setTenantId(session.getTenantId() == null ? TENANT_MAIN : session.getTenantId());
        m.setSessionNo(sessionNo);
        m.setSenderType(CsSenderType.AGENT.name());   // 客服坐席，不是代理商
        m.setSenderNo(senderNo);
        m.setContent(content);
        m.setAttach(attach);
        m.setCreatedAt(LocalDateTime.now().format(TS));
        messageMapper.insert(m);

        // 冗余摘要同步：会话列表直出这一列，漏更就会显示成上一条
        session.setLastMessage(summarize(content));
        mapper.updateById(session);

        return toVO(m);
    }

    @Override
    @Transactional
    public String openFor(String cUserNo, String firstMessage) {
        CsSession active = mapper.selectOne(new LambdaQueryWrapper<CsSession>()
                .eq(CsSession::getCUserNo, cUserNo)
                .eq(CsSession::getStatus, CsSessionStatus.ACTIVE.name())
                .orderByDesc(CsSession::getId)
                .last("limit 1"));
        if (active != null) return active.getSessionNo(); // 幂等：一人同时只有一个活跃会话

        CsSession e = new CsSession();
        e.setSessionNo(nextSessionNo());
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setStatus(CsSessionStatus.ACTIVE.name());
        e.setLastMessage(summarize(firstMessage));
        mapper.insert(e);

        if (firstMessage != null && !firstMessage.isBlank()) {
            CsMessage m = new CsMessage();
            m.setTenantId(TENANT_MAIN);
            m.setSessionNo(e.getSessionNo());
            m.setSenderType(CsSenderType.USER.name());
            m.setSenderNo(cUserNo);
            m.setContent(firstMessage);
            m.setCreatedAt(LocalDateTime.now().format(TS));
            messageMapper.insert(m);
        }
        return e.getSessionNo();
    }

    // ——————————————————————— 内部 ———————————————————————

    /** 扫描同前缀最大号 +1（[db-design §1.4.1]）。前缀 CS，注意与 CS_TICKET 的 TK 区分。 */
    private String nextSessionNo() {
        CsSession top = mapper.selectOne(new LambdaQueryWrapper<CsSession>()
                .likeRight(CsSession::getSessionNo, BizKey.CS_SESSION)
                .orderByDesc(CsSession::getSessionNo)
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getSessionNo() != null && top.getSessionNo().length() > BizKey.CS_SESSION.length()) {
            String digits = top.getSessionNo().substring(BizKey.CS_SESSION.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号
                }
            }
        }
        return BizKey.CS_SESSION + String.format("%06d", n + 1);
    }

    private static String summarize(String s) {
        if (s == null) return null;
        return s.length() <= SUMMARY_MAX ? s : s.substring(0, SUMMARY_MAX);
    }

    private static CsSessionVO toVO(CsSession e) {
        return new CsSessionVO(e.getSessionNo(), e.getCUserNo(), e.getAgentName(),
                e.getLastMessage(), e.getStatus(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().format(TS));
    }

    private static CsMessageVO toVO(CsMessage e) {
        return new CsMessageVO(e.getId(), e.getSessionNo(), e.getSenderType(), e.getSenderNo(),
                e.getContent(), e.getAttach(), e.getCreatedAt());
    }
}
