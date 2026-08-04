package ai.neargo.sharehub.user.asset.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MemberRow;
import ai.neargo.sharehub.user.asset.entity.UsrMembership;

/**
 * 会员 / 次卡（usr_membership + mbr_plan）。有有效期与续费语义 → 手写。
 *
 * <p>{@code usr_membership} 在 DDL 里没有 {@code version}/{@code deleted}，实体不继承
 * {@code BaseEntity}，结构上接不了 {@code AbstractCrudService}。
 */
public interface MembershipService {

    /**
     * 运营端会员列表。
     *
     * @param level SILVER/GOLD/PLATINUM；空则全部
     */
    PageResult<MemberRow> page(Integer page, Integer size, String keyword, String level);

    /** 按 C 端用户号取会员；无则 {@code null}。 */
    MemberRow get(String cUserNo);

    /** 开通 / 调整会员（upsert，按 {@code cUserNo} —— 端点是 {@code /members/{userNo}}）。 */
    MemberRow save(UsrMembership body);

    /** C 端会员方案列表：全部在售 {@code mbr_plan} + 标注该用户已开通的那个（active/expireAt）。 */
    java.util.List<ai.neargo.sharehub.user.asset.dto.UserAssetDtos.MembershipPlanVO> plansFor(String cUserNo);
}
