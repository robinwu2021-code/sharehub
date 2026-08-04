package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.CouponTplVO;
import ai.neargo.sharehub.user.marketing.entity.CouponTpl;

/**
 * 券模板（coupon_tpl）。发放规则/库存是配置 → 继承通用 CRUD。
 * 「发券」这个动作有库存与防重复领的业务规则，落在 {@link UserCouponService}。
 */
public interface CouponTplService extends CrudService<CouponTpl, CouponTplVO> {
}
