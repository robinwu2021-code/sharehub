package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.UserCouponVO;

import java.util.List;

/**
 * 用户券（usr_coupon）—— **有业务规则的聚合根**（库存扣减、防重复领、状态流转），
 * 故手写实现而非继承通用 CRUD。
 *
 * <p><b>属主约束</b>：[db-design §10] 规定 usr_coupon 是 C 端属主查询表，
 * 所有读写都必须能按 {@code cUserNo} 限定。C 端调用时 {@code cUserNo} 只能来自
 * {@code ConsumerContext}，运营端调用时才允许由参数指定。
 */
public interface UserCouponService {

    /**
     * 分页查用户券。
     *
     * @param cUserNo 属主过滤；C 端必传（由会话决定），运营端可空表示全量
     */
    PageResult<UserCouponVO> page(Integer page, Integer size, String cUserNo, String status);

    /**
     * 定向发券（{@code POST /api/user/coupons/{tplNo}/issue}）—— 运营把某模板发给一批用户。
     *
     * <p>规则：模板必须 ACTIVE；{@code stock>0} 时按剩余库存（{@code stock - issued}）截断；
     * 同一模板对同一用户已有未使用券则跳过（防重复发）。返回实际发出的券。
     */
    List<UserCouponVO> issue(String tplNo, List<String> cUserNos, String expireAt);

    /**
     * C 端领券（{@code POST /mp/user/coupons/{couponNo}/claim}）。
     *
     * <p>路径上的 {@code couponNo} 是**领券中心列表项**，即券模板号；沿用前端命名。
     * 幂等：该用户对该模板已有 UNUSED 券则直接返回已有的，不发第二张。
     */
    UserCouponVO claim(String cUserNo, String tplNo);

    /** 券发放记录（append 表，只增不改）。 */
    ai.neargo.common.core.PageResult<?> pageIssueRecords(Integer page, Integer size, String couponNo);
}
