package ai.neargo.sharehub.cs.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.cs.dto.CsDtos.CsMessageVO;
import ai.neargo.sharehub.cs.dto.CsDtos.CsSessionVO;

import java.util.List;

/**
 * 客服会话（cs_session + cs_message）。会话有开闭状态、消息是 append 流 → 手写实现。
 */
public interface CsSessionService {

    /** 运营端会话列表（{@code GET /api/ops/cs/sessions}）。 */
    PageResult<CsSessionVO> page(Integer page, Integer size, String keyword, String status);

    /**
     * 会话消息（{@code GET /api/ops/cs/sessions/{sessionNo}/messages}），按时间正序。
     * append 表不分页取整段：单次会话消息量有界，分页反而让前端拼接麻烦。
     */
    List<CsMessageVO> messages(String sessionNo, Integer limit);

    /**
     * 客服回复（{@code POST /api/ops/cs/sessions/{sessionNo}/messages}）。
     * 落一条 {@code sender_type=AGENT} 的消息，并同步刷新会话的 {@code lastMessage}
     * —— 列表页靠这个冗余列直出，不能只写 append 表。
     *
     * @param senderNo 回复人 employee_no
     */
    CsMessageVO reply(String sessionNo, String senderNo, String content, String attach);

    /**
     * 为某用户开一个人工会话（报障分流 TO_CS 时调用）。
     *
     * <p><b>幂等</b>：该用户已有 ACTIVE 会话则复用，不开第二个 —— 否则客服端会看到
     * 同一个人的多条并行会话，回复落到哪个全凭运气。
     *
     * @return 会话业务键
     */
    String openFor(String cUserNo, String firstMessage);
}
