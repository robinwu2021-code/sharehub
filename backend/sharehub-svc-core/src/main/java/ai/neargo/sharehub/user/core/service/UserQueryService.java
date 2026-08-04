package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.CUserRow;

/**
 * 用户档案读模型（{@code usr_user} ⋈ {@code usr_identity} ⋈ {@code usr_blacklist}）。
 *
 * <p>{@code phone} 出参即脱敏（[api §1.6]）：明文只在 {@code pb_pii}，本服务只从
 * {@code usr_identity}（PHONE 渠道）取号码并掩码，绝不直出。
 *
 * <p>{@code orders} 累计单数是跨域数据（订单域），本服务恒出 0，由 portal 门面用
 * {@code RentOrderService.countByUsers} 回填 —— 用户域不反向依赖交易域（分层 L1 ↛ L3）。
 */
public interface UserQueryService {

    /** 用户分页。{@code keyword} 匹配用户号/昵称。 */
    PageResult<CUserRow> page(Integer page, Integer size, String keyword);

    /** 单个用户档案；不存在返回 {@code null}（404 语义由门面决定）。 */
    CUserRow get(String cUserNo);

    /**
     * C 端资料编辑：仅 {@code nickname}/{@code avatar}（null = 不改）。
     * {@code email} 无列（PII 归 {@code pb_pii}，分库未建），门面层显式拒绝而非静默丢弃。
     */
    CUserRow updateProfile(String cUserNo, String nickname, String avatar);
}
