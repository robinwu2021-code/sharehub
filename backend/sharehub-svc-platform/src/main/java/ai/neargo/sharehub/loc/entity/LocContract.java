package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 进场合同实体（loc_contract，场地方×站点）。镜像 Contract + 审计列。 */
@Data
@TableName("loc_contract")
public class LocContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private String tenantId;
    /** 场地方编号。与 {@link LocSite#getVenueNo()} 一样，列早就有、实体此前漏映射。 */
    private String venueNo;

    /** 站点编号 —— 合同是**按站点**签的，这是「站点级分成比例」的唯一存放处。 */
    private String siteNo;

    private String venueName;
    private String siteName;
    private Double shareRate;
    private Double entryFee;
    private String startAt;
    private String endAt;
    /** [db-design §1.5]：带金额语义的表一律有币种。 */
    private String currency;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
