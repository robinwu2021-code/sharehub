package ai.neargo.sharehub.loc.ext.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线索跟进记录（{@code loc_lead_follow}）。**append 表**，无 version/deleted。
 *
 * <p><b>跟进是流水不是状态</b>：每次联系都留一条 ——「跟了几次、每次说了什么」
 * 是判断线索质量与销售投入的依据，覆盖式的「最近跟进」把这些全丢了。
 */
@Data
@TableName("loc_lead_follow")
public class LocLeadFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String followNo;
    private String tenantId;
    private String leadNo;

    /** CALL / VISIT / WECHAT / EMAIL。 */
    private String channel;

    /** 来源阶段；首条建档跟进为 null。 */
    private String fromStage;

    /** 跟进后阶段（可与来源相同 = 本次未推进）。 */
    private String toStage;

    private String owner;
    private String content;

    /** 下次跟进计划日；空 = 未约。 */
    private LocalDate nextAt;

    private LocalDateTime createdAt;
    private String createdBy;
}
