package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.SiteLifecycle;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.StageChangeReq;

/**
 * 门店生命周期（阶段流转有留痕副作用 → 手写，不继承通用 CRUD）。
 *
 * <p>不变量：**每一次阶段变更都必须在 {@code loc_site_lifecycle_log} 落一行**，
 * 且与主表更新同事务 —— 否则「当前阶段」与「怎么走到这一步」会对不上。
 */
public interface SiteLifecycleService {

    PageResult<SiteLifecycle> page(Integer page, Integer size, String keyword, String stage);

    SiteLifecycle get(String siteNo);

    /**
     * 阶段流转（站点无档案时**自动建档**，首次的 {@code fromStage} 记为空）。
     *
     * @param req {@code gmvLtm} 为阶段决策快照，由调用方在流转时刻算好传入；不做定时回刷
     */
    SiteLifecycle changeStage(String siteNo, StageChangeReq req);
}
