package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站点现场勘测（loc_site_survey，追加表，V107）。 */
@Data
@TableName("loc_site_survey")
public class LocSiteSurvey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String surveyNo;
    private String tenantId;
    private String siteNo;
    private String signalLevel;
    private Boolean powerOk;
    private String placementNote;
    private String fileNos;
    private String result;
    private String note;
    private String surveyedBy;
    private LocalDateTime surveyedAt;
    private LocalDateTime createdAt;
}
