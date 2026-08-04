package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.NoticeVO;
import ai.neargo.sharehub.user.marketing.entity.MktNotice;

import java.util.List;

/**
 * 公告管理。运营侧是纯配置读写 → 继承通用 CRUD；C 端读取另有可见性规则，故额外一个方法。
 */
public interface NoticeService extends CrudService<MktNotice, NoticeVO> {

    /**
     * C 端可见公告（{@code GET /mp/notice}）—— 首页公告条 + 公告页共用。
     *
     * <p>可见性 = {@code status=PUBLISHED} 且当前时刻落在 {@code [startAt, endAt]} 内
     * （两端为空视为不限）。排序：置顶优先，其次最新。**生效期是服务端判定的**，
     * 不靠运营手动改状态，否则活动一过就得有人记得下线。
     */
    List<NoticeVO> visibleNotices(Integer limit);
}
