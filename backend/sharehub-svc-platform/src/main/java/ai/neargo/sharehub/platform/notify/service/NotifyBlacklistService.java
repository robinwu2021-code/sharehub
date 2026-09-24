package ai.neargo.sharehub.platform.notify.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyBlacklistVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyBlacklist;

/** 触达拉黑（全渠道，含 {@code ALL}）。解除是软删（{@code status=RELEASED} + 留痕），不物理删。 */
public interface NotifyBlacklistService {

    PageResult<NotifyBlacklistVO> page(Integer page, Integer size, String keyword,
                                       String channel, String reason, String status);

    /** 拉黑：取号 {@code NBL*}、脱敏 target、置 {@code ACTIVE}。 */
    NotifyBlacklistVO block(NotifyBlacklist body);

    /**
     * 解除（软删）：{@code ACTIVE → RELEASED}，回填 {@code releasedAt}/{@code releasedBy}。
     * 已是 {@code RELEASED} 再解除属非法流转，抛 {@link IllegalArgumentException}。
     */
    NotifyBlacklistVO release(String blockNo, String operator);

    /**
     * 修改一条拉黑记录。**只改 channel / reason**。
     *
     * <p><b>target 冻结</b>：它是脱敏后存的（`+9715****1234`），改掉等于换了一个人被拉黑，
     * 而从掩码上根本看不出换没换。要拉黑别人就新建一条。
     *
     * <p><b>已解除的不可改</b>：那是一条历史记录，解除动作是对当时那份内容做的。
     */
    NotifyBlacklistVO update(String blockNo, NotifyBlacklist body);

    /**
     * <b>发送前必查</b>：目标在该渠道是否被拉黑。
     *
     * <p>命中规则：{@code status=ACTIVE} 且未过 {@code expireAt}，且渠道匹配 ——
     * 记录的 {@code channel=ALL} 命中**任意**渠道（全渠道拉黑），否则精确相等。
     * 入参可传明文，内部按脱敏值比对（表里存的就是脱敏值）。
     */
    boolean isBlocked(String target, String channel);
}
