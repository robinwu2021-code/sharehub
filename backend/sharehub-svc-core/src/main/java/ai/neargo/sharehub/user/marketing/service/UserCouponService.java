package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ClaimableCouponVO;
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
     * 按人群定向发券（运营端「发放」按钮走的就是这条）。
     *
     * <p><b>此前这条路根本不通</b>：端点读 {@code cUserNos}，而前端发的是人群规格
     * （{@code targetType}/{@code targetValue}/{@code quantity}）—— 键对不上，
     * {@link #issue} 拿到 null 直接 {@code return List.of()}，
     * <b>一张券都不发而 HTTP 200</b>。
     *
     * <p><b>只有 {@code USER_LIST} 能真发</b>：把逗号分隔的用户号解析出来即可。
     * {@code ALL / MEMBER_LEVEL / SEGMENT} <b>显式拒绝</b>而不是发 0 张 ——
     * 后端至今没有「人群 → 用户列表」的解析（推送侧也只拼了个人群**标签**，
     * 从不落实到人；{@code mkt_segment} 表根本不存在）。
     * 发 0 张 + 200 是最坏的选择：运营以为发出去了。
     *
     * @param quantity 发放上限；null/≤0 = 不限（仍受模板库存约束）
     */
    ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponIssueResultVO issueToAudience(
            String tplNo, String targetType, String targetValue, Integer quantity);

    /**
     * 领券中心（{@code GET /mp/user/coupons/claimable}）—— 当前**可领**的券模板。
     *
     * <p>没有这个接口时 {@link #claim} 是**够不着的**：C 端另一个列表
     * （{@code GET /mp/user/coupons}）返的是用户已有的券实例，从里面拿不到任何可领的模板号。
     * 于是「领券中心」在文档里存在、在后端存在，唯独在界面上无从发起 —— 不报错，只是永远没有券可领。
     *
     * <p>过滤：{@code status=ACTIVE} 且未归档；{@code stock>0} 时还要 {@code issued < stock}。
     * 每行带 {@code claimed}（该用户是否已有未使用券），供按钮置灰。
     */
    List<ClaimableCouponVO> claimable(String cUserNo);

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
