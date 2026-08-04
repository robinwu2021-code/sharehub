package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.UserBlacklist;

/**
 * 用户黑名单（usr_blacklist）。有状态流转 → 手写聚合根，不走通用 CRUD。
 *
 * <p><b>核心不变量</b>：黑名单是<b>独立表</b>而非 {@code usr_credit} 上的布尔列，
 * 因为解除必须留痕（{@code released_at}/{@code released_by}）。因此：
 * <ul>
 *   <li>拉黑 = 新增 {@code ACTIVE} 行（同用户已有 ACTIVE 则幂等返回原记录）；</li>
 *   <li>解除 = 把该行改 {@code RELEASED} + 回填解除人/时间，<b>记录保留，绝不物理删</b>。</li>
 * </ul>
 */
public interface UserBlacklistService {

    /**
     * 黑名单分页。
     *
     * @param status ACTIVE/RELEASED；空则含全部（解除记录也要能查到，这正是独立表的意义）
     */
    PageResult<UserBlacklist> page(Integer page, Integer size, String keyword, String status);

    /** 拉黑。同一用户已有 ACTIVE 记录时幂等返回原记录，不重复插入。 */
    UserBlacklist block(String cUserNo, String reason, String operatorNo);

    /**
     * 解除拉黑（软状态迁移）：{@code status=RELEASED} + {@code releasedAt}/{@code releasedBy}。
     *
     * @throws IllegalStateException 该用户当前没有 ACTIVE 记录（非法迁移，主控接 409）
     */
    UserBlacklist release(String cUserNo, String operatorNo);
}
