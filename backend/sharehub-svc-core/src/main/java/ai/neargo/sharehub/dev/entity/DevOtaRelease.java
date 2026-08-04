package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

/**
 * OTA 固件版本（dev_ota_release，[db-design §3.1]）。
 *
 * <p><b>为什么不继承 BaseEntity</b>：本表的 {@code version} 列是<b>固件版本号</b>（VARCHAR），
 * 乐观锁列在 DDL 里被让位命名为 {@code version_col}。{@link ai.neargo.sharehub.common.BaseEntity}
 * 的 {@code version} 字段固定映射 {@code version} 列，继承会把 Long 版本号写进固件版本列，
 * 故本实体自持审计列，并把乐观锁显式映射到 {@code version_col}。
 */
@Data
@TableName("dev_ota_release")
public class DevOtaRelease {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String releaseNo;

    private String tenantId;

    /** 固件类型（主控/仓门/通信模组…）。 */
    private String fwType;

    /** 适用供应商；空表示通用。 */
    private String vendorCode;

    /** 固件版本号（对应 DDL 的 {@code version} 列，非乐观锁）。 */
    private String version;

    /** 版本序号，用于比大小判断可升级。 */
    private Integer versionCode;

    private String artifactUrl;

    private String checksum;

    /** 是否强制升级（TINYINT(1)）。 */
    private Integer mandatory;

    /** DRAFT / PUBLISHED / PAUSED / COMPLETED。 */
    private String status;

    private String releaseNotes;

    private String createdAt;

    private String updatedAt;

    /** 乐观锁 —— 让位给固件版本列，落在 {@code version_col}。 */
    @Version
    @TableField("version_col")
    private Long lockVersion;

    @TableLogic
    private Integer deleted;
}
