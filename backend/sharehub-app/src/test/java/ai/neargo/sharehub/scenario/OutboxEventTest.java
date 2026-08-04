package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.common.event.entity.SysOutbox;
import ai.neargo.sharehub.common.event.mapper.SysOutboxMapper;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务性发件箱的三条不变量（ADR-019）。
 *
 * <p>这些断言检查的是**机制**而非某个业务结果 —— outbox 的价值全在
 * 「原子」与「不丢」两点上，业务测试碰不到它们。
 */
class OutboxEventTest extends ApiTestSupport {

    @Autowired
    private SysOutboxMapper outbox;

    private List<SysOutbox> assignEvents() {
        return outbox.selectList(new LambdaQueryWrapper<SysOutbox>()
                .eq(SysOutbox::getEventType, "ASSET_ASSIGNED")
                .orderByDesc(SysOutbox::getId));
    }

    private Map<String, Object> assignReq(String targetNo) {
        Map<String, Object> req = new HashMap<>();
        req.put("targetType", "SITE");
        req.put("targetNo", targetNo);
        req.put("agentNo", "AG002");
        req.put("action", "ASSIGN");
        req.put("operator", "test.admin");
        return req;
    }

    /** 划拨成功 → 事件落 outbox，且载荷自带 locationNos（消费方无需回查）。 */
    @Test
    void assignment_writes_outbox_with_self_contained_payload() {
        int before = assignEvents().size();

        post("/api/agent/assignments", assignReq("ST300"), login("ADMIN")).okData();

        List<SysOutbox> after = assignEvents();
        assertThat(after.size()).as("划拨应产生一条 outbox 事件").isEqualTo(before + 1);

        SysOutbox e = after.get(0);
        assertThat(e.getAggregateType()).isEqualTo("AgtAssignment");
        assertThat(e.getPayload())
                .as("事件必须自带 locationNos —— 让消费方回查等于把同步调用藏进事件")
                .contains("locationNos");
        assertThat(e.getPayload()).contains("ST300");
    }

    /** 投递成功后状态为 SENT；停在 PENDING 说明 afterCommit 回调没触发。 */
    @Test
    void delivered_events_are_marked_sent() {
        post("/api/agent/assignments", assignReq("ST300"), login("ADMIN")).okData();

        SysOutbox e = assignEvents().get(0);
        assertThat(e.getStatus()).isEqualTo("SENT");
        assertThat(e.getSentAt()).isNotNull();
    }

    /**
     * <b>划拨失败（对象不存在）→ 事务回滚 → 不留事件。</b>
     *
     * <p>这是 outbox 存在的核心理由：若在事务内直接发消息，回滚后事件已经出去了，
     * 消费方会按一个从未发生的事实动作，且无法回滚。本用例锁死这一条 ——
     * 它一旦失效，整套最终一致的正确性基础就没了。
     */
    @Test
    void rolled_back_assignment_leaves_no_event() {
        int before = assignEvents().size();

        assertThat(post("/api/agent/assignments", assignReq("ST-NOT-EXIST-XYZ"), login("ADMIN")).status)
                .as("划拨不存在的站点应失败").isNotEqualTo(200);

        assertThat(assignEvents().size())
                .as("事务回滚后不得留下事件 —— 否则消费方会按未发生的事实动作")
                .isEqualTo(before);
    }
}
