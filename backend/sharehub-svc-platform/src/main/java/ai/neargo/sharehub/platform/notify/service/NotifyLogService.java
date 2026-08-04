package ai.neargo.sharehub.platform.notify.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogStats;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyLog;

/**
 * 发送记录（append 表）——只有「查」和「追加」，没有改和删。
 *
 * <p>排序是**受控**的（[api/README §1.4]）：只接受 {@code sentAt} / {@code cost} 两个字段名，
 * 其它一律拒绝。列名会被拼进 SQL，放开等于开一个注入面；白名单与
 * {@code ops-web/lib/api/query.ts} 的定义一一对应。
 */
public interface NotifyLogService {

    /**
     * @param sort 仅允许 {@code sentAt} / {@code cost}；其它值抛 {@link IllegalArgumentException}
     * @param dir  {@code asc} / {@code desc}（大小写不敏感），默认 desc
     */
    PageResult<NotifyLogVO> page(Integer page, Integer size, String keyword,
                                 String channel, String status, String scene, String sort, String dir);

    /** 页头统计：**全量口径**的今日发送 / 失败 / 失败率 / 成本，不是当前分页的合计。 */
    NotifyLogStats stats();

    /** 追加一条发送记录；{@code target} 在此方法内脱敏后落库，调用方可传明文。 */
    NotifyLogVO append(NotifyLog e);

    /**
     * 重发一条发送记录。
     *
     * <p><b>只接受幂等键</b>：目标/渠道/模板一律沿用原记录 —— 允许运营改这些，
     * 等于换了一次全新发送，那应该走新建而不是重发。
     *
     * <p><b>新增一条记录，原记录一字不改</b>：审计要看得见「发了两次」。
     *
     * @throws IllegalStateException 幂等键重复（同一次操作被提交两次）
     */
    NotifyLogVO resend(String logNo, String idempotencyKey);
}
