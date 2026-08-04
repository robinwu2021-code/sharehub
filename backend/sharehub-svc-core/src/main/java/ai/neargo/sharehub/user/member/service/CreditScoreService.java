package ai.neargo.sharehub.user.member.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreAdjustReq;
import ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreChange;

/** 信用分调整与变更流水。 */
public interface CreditScoreService {

    /**
     * 调整信用分。
     *
     * <p><b>原因必填</b> —— 没有原因的调分等于没有记录，风控争议时无法解释。
     * <p><b>同事务落流水</b> —— 只改分不留流水，「分是怎么变成今天这样的」就永远查不出来。
     */
    CreditScoreChange adjust(String cUserNo, CreditScoreAdjustReq req);

    PageResult<CreditScoreChange> pageChanges(Integer page, Integer size, String cUserNo);
}
