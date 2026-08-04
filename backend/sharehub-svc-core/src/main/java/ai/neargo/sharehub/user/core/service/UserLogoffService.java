package ai.neargo.sharehub.user.core.service;

import ai.neargo.sharehub.user.core.dto.UserCoreDtos.LogoffItem;

/**
 * 账号注销（usr_logoff，C-AC-05）—— PDPL 冷静期。
 *
 * <p>注销<b>不是立即删数据</b>：先落 {@code PENDING} 并给出 {@code coolingUntil}，
 * 期内用户可撤销（{@code CANCELLED}）；到期后由清除作业执行并回填 {@code purgedAt} + {@code DONE}。
 * 冷静期天数与「哪些表随注销物理清除、哪些匿名化保留用于财务留痕」待法务确认
 * （[db-design §十二 待确认 5]），本骨架先用默认值。
 */
public interface UserLogoffService {

    /** 当前注销申请；无则 {@code null}。 */
    LogoffItem current(String cUserNo);

    /**
     * 提交注销申请。
     *
     * @throws IllegalStateException 已有 PENDING 申请（同一用户至多一条，DDL 不设 UK 由此保证）
     */
    LogoffItem apply(String cUserNo);

    /**
     * 冷静期内撤销注销。
     *
     * @throws IllegalStateException 无 PENDING 申请，或冷静期已过（此时数据可能已开始清除）
     */
    LogoffItem cancel(String cUserNo);
}
