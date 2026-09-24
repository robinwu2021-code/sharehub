package ai.neargo.sharehub.user.marketing;

import java.util.Arrays;
import java.util.Optional;

/**
 * 推送/发券的受众口径（{@code mkt_push.audience_type}，词表见 V75 的列注释）。
 *
 * <p>与 {@code audience_value} 成对：{@link #MEMBER_LEVEL}→等级码、{@link #SEGMENT}→分群号、
 * {@link #USER_LIST}→逗号分隔用户号、{@link #ALL}→空。
 *
 * <h3>为什么收进枚举</h3>
 * 这四个值此前一半是裸字符串、一半在 {@code switch} 的 case 上。
 * <b>case 上打错字不会报错</b> —— 它只是静默落到 {@code default}，
 * 于是一条「发给会员等级 GOLD」的推送在列表上显示成「全部用户」。
 * 而运营看到的是一个**看起来正常**的标签，不会有人去怀疑它。
 *
 * <h3>与运营端同名</h3>
 * 运营端 {@code lib/types/marketing.ts} 的 {@code AudienceType} 同名同值，
 * 这一对因此进入两端同名词表比对（{@code StatusVocabularyAcrossEndsTest}）。
 */
public enum AudienceType {

    /** 全量用户；{@code audience_value} 为空。 */
    ALL,
    /** 按会员等级；值是等级码。 */
    MEMBER_LEVEL,
    /** 按分群；值是分群号。 */
    SEGMENT,
    /** 指定用户；值是逗号分隔的用户号。 */
    USER_LIST;

    public static Optional<AudienceType> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
