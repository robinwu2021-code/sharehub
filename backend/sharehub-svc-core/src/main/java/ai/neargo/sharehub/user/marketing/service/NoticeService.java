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

    /**
     * 发布 / 下线。**是动作端点，不是保存字段。**
     *
     * <h3>为什么必须单独成一个动作</h3>
     * 转写入 DTO 那批把 {@code status} 从保存端点的入参里去掉了（对的：那条路等于
     * 「随便写任何状态」，一条状态机的边都不用走）。但**没有补上替代的动作**，
     * 而运营台的「发布」按钮还在往保存端点发 {@code {noticeNo, status}}。
     * 2026-09-27 实测两种失败形态，都不说真正的原因：
     * <ul>
     *   <li>只发 {@code {noticeNo, status}}（前端的真实请求）→ <b>400「公告标题必填」</b>
     *       —— 运营根本没碰标题；</li>
     *   <li>带全量字段再加 {@code status} → <b>200，而库里仍是 DRAFT</b>。</li>
     * </ul>
     * 结果是**公告发不出去，C 端公告永远为空**（C 端只读 {@code PUBLISHED}）。
     *
     * <p>形状照 {@code archive/unarchive}：状态只从动作端点改，保存端点碰不到它 ——
     * 与 {@code beforeUpdate} 里「保存端点也能写它 = 给归档开了第二条不走审计的路」同一个理由。
     *
     * @param publish {@code true} 发布，{@code false} 下线
     * @throws ai.neargo.sharehub.common.BizException 公告不存在
     */
    NoticeVO setPublished(String noticeNo, boolean publish);
}
