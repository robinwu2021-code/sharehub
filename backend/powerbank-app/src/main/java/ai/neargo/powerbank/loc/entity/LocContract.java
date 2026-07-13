package ai.neargo.powerbank.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 进场合同实体（loc_contract，场地方×站点）。镜像 Dto.Contract + 审计列。 */
@Data
@TableName("loc_contract")
public class LocContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private String tenantId;
    private String venueName;
    private String siteName;
    private Double shareRate;
    private Double entryFee;
    private String startAt;
    private String endAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
