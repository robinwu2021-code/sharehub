package ai.neargo.sharehub.user.marketing;

import java.util.List;
import java.util.Map;

/**
 * 活动启停状态机（与 ops-web {@code CAMPAIGN_TRANSITIONS} 同一张表）。
 *
 * <p><b>合法迁移只声明一次</b>：页面用它决定出不出按钮，服务端用它决定拒不拒绝。
 * 两边各写一串 if 的话，「已结束的活动被人点活」这类洞会从缝里漏过去 ——
 * 页面按钮藏了，但直接调接口照样能改。
 *
 * <p>写法与 {@code SettlementStateMachine}/{@code WithdrawalStateMachine} 一致。
 */
public final class CampaignStateMachine {

    private CampaignStateMachine() {
    }

    /** action → (允许的来源状态, 目标状态)。 */
    /**
     * action → 迁移。<b>状态是 {@link CampaignStatus}</b>，动作名仍是字符串
     * （与运营端 {@code CAMPAIGN_TRANSITIONS} 的 key 一致，是接口词汇不是状态）。
     */
    private static final Map<String, Transition> T = Map.of(
            "start", new Transition(List.of(CampaignStatus.DRAFT, CampaignStatus.PAUSED),
                                    CampaignStatus.RUNNING, "启动"),
            "pause", new Transition(List.of(CampaignStatus.RUNNING),
                                    CampaignStatus.PAUSED, "暂停"),
            "end", new Transition(List.of(CampaignStatus.RUNNING, CampaignStatus.PAUSED),
                                  CampaignStatus.ENDED, "结束"));

    public record Transition(List<CampaignStatus> from, CampaignStatus to, String label) {
    }

    /**
     * 校验并返回目标状态。
     *
     * @throws IllegalArgumentException 未知动作
     * @throws IllegalStateException    非法迁移（如已结束的活动再启动）
     */
    public static String next(String currentStatus, String action) {
        Transition t = T.get(action);
        if (t == null) {
            throw new IllegalArgumentException("未知活动动作: " + action + "，可用: " + T.keySet());
        }
        if (!t.from().contains(CampaignStatus.of(currentStatus))) {
            throw new IllegalStateException(String.format(
                    "活动状态 %s 不能执行「%s」，允许的来源状态: %s",
                    currentStatus, t.label(), t.from()));
        }
        return t.to().name();
    }
}
