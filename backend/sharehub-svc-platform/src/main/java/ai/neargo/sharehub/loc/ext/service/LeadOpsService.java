package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConversion;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConvertReq;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadTickResult;

import java.time.LocalDateTime;

/**
 * 商机的动作（对齐清单 B7–B9）：认领线索池、签约转化、跟进提醒 / 回收 / 竞品到期重新激活。
 * 属性编辑与阶段推进在 {@link LeadService} / {@link LeadFollowService}。
 */
public interface LeadOpsService {

    /** 从公共线索池认领：负责人改成我、重新计时。被别人先认领 → 409。 */
    Lead claim(String leadNo);

    /**
     * 签约转化（E1）：生成或关联场地方 → 生成或关联站点（筹备中）→ 带谈判条款的合同草稿（记来源商机）→ 商机 SIGNED。
     * 一个事务：任何一步失败都不留半截档案。已转化过 → 409。
     */
    LeadConversion convert(String leadNo, LeadConvertReq req);

    /** 定时：超 N 天无跟进提醒负责人；超 M 天回收到线索池；竞品独家到期前 K 天把 LOST 商机重新激活。 */
    LeadTickResult tick(LocalDateTime now);
}
