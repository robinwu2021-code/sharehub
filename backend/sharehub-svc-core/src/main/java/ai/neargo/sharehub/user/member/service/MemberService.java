package ai.neargo.sharehub.user.member.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberBenefit;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCard;
import ai.neargo.sharehub.user.member.dto.MemberDtos.MemberCardGrantReq;

import java.util.List;

/** 会员等级权益与会员卡。 */
public interface MemberService {

    /** 权益列表，**按等级由低到高**返回（页面排序与单调性校验共用同一顺序）。 */
    List<MemberBenefit> benefits();

    /**
     * 保存某等级的权益。
     *
     * <p><b>写入时强制单调性</b>：权益必须随等级变好 —— 折扣更低、免费时长更长、
     * 升级门槛更高。黄金比铂金还便宜的话，会员体系当场失去意义，
     * 所以这条在**写入时**拦，而不是靠运营自觉。
     */
    MemberBenefit saveBenefit(String level, MemberBenefit in);

    PageResult<MemberCard> pageCards(Integer page, Integer size, String keyword, String level);

    /** 发卡：给用户开通会员。 */
    MemberCard grantCard(MemberCardGrantReq req);
}
