package ai.neargo.sharehub.user.marketing.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 推送触达（mkt_push，[db-design §6.3]）—— App Push / 小程序订阅消息的批量下发单。
 *
 * <p>{@code sentAt} 为空的语义统一是「尚未发生」（[db-design §1.5]），即 status=DRAFT。
 * 实际下发要走触达渠道并落 {@code notify_log}（platform 域），本表只管「发了什么、发给谁、发了多少」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mkt_push")
public class MktPush extends BaseEntity {

    private String pushNo;

    private String title;

    private String content;

    /** APP_PUSH / SUBSCRIBE。 */
    private String channel;

    /**
     * 人群的**可读标签**，由 {@link #audienceType}/{@link #audienceValue} 派生（服务端拼）。
     * 列表直接展示这一列，页面不必再解一遍。
     */
    private String audience;

    /**
     * 人群类型：ALL / MEMBER_LEVEL / SEGMENT / USER_LIST。
     *
     * V75 之前**没有这两列**，而实体就是请求体 —— 前端提交的 audienceType/audienceValue
     * 在这里没有对应字段，被 Jackson 静默丢弃：运营选的人群根本存不进去，
     * 而 mock 下一切正常。这类分叉只在切真后端时才露出来。
     */
    private String audienceType;

    /** MEMBER_LEVEL→等级码；SEGMENT→segmentNo；USER_LIST→逗号分隔用户号；ALL→空。 */
    private String audienceValue;

    /** 实发人数（下发后回填）。 */
    private Integer sentCount;

    /**
     * DRAFT / SCHEDULED / SENDING / SENT（V75 起四态，**SENT 是终态不可重发**）。
     * 与前端 {@code PUSH_TRANSITIONS} 逐条对齐；迁移前只有 DRAFT/SENT 两态。
     */
    private String status;

    /** 下发时刻；空 = 尚未发送。 */
    private String sentAt;

    /** 排期发送时刻；空 = 立即发送。到点由扫描取走（V75 的 idx_push_due）。 */
    private String scheduledAt;

    /** 目标人数：发送时按人群规模落库。与 {@link #successCount} 一起，运营才看得出触达率。 */
    private Integer targetCount;

    /** 成功触达人数（≤ targetCount）。 */
    private Integer successCount;

    /** 操作人姓名快照：列表直出，不为渲染再查一次员工表（同本仓其它 *Name 快照列）。 */
    private String operatorName;

    /**
     * 幂等键：同一键只发一次。
     * 唯一性由 V75 的 {@code uk_push_idem (tenant_id, idempotency_key)} 保证 ——
     * 只在应用层「查一下有没有」的话，两个请求并发时两边都查不到，然后都发。
     */
    private String idempotencyKey;
}
