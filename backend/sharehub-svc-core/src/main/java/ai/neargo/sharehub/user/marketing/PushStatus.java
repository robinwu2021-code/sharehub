package ai.neargo.sharehub.user.marketing;

import java.util.Arrays;
import java.util.Optional;

/**
 * 定时推送的生命周期（{@code mkt_push.status}，词表见 V75 的列注释）。
 *
 * <pre>
 *   DRAFT ──排期──▶ SCHEDULED ──到点/立即──▶ SENDING ──收尾──▶ SENT
 *     └────────────────立即发送───────────────┘
 * </pre>
 *
 * <h3>为什么收进枚举</h3>
 * 这四个值原先是 {@code PushServiceImpl} 里的十处裸字符串，其中一半是
 * <b>判据</b>（{@code requireStatus(e, "发送", "DRAFT", "SCHEDULED")}）而不只是赋值。
 * 判据里打错一个字母不会报错 —— 它只是让那个状态<b>永远不满足条件</b>，
 * 表现是「这个按钮点了没反应」，而不是异常。
 *
 * <h3>与运营端同名</h3>
 * 运营端 {@code lib/types/marketing.ts} 的 {@code PushStatus} 是同名同值的具名类型，
 * 于是这一对进入两端同名词表比对（{@code StatusVocabularyAcrossEndsTest}）——
 * 以后任一端加档、改名、漏档都会当场报红。
 */
public enum PushStatus {

    /** 草稿：可改可删，尚未进入发送流程。 */
    DRAFT,
    /** 已排期：到点自动转 {@link #SENDING}。 */
    SCHEDULED,
    /** 发送中：已开始投递，收尾后转 {@link #SENT}。 */
    SENDING,
    /** 已发送 —— 终态，不可重发（重复收尾按幂等处理）。 */
    SENT;

    public static Optional<PushStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
