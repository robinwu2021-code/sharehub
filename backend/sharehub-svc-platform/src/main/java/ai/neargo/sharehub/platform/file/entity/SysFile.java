package ai.neargo.sharehub.platform.file.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 文件元数据（sys_file）。字节在对象存储，这里只记「它是谁的、在哪、能不能看」。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_file")
public class SysFile extends BaseEntity {
    private String fileNo;
    private String category;
    private String status;
    private String bizType;
    private String bizNo;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String storage;
    private String bucket;
    private String objectKey;
    private Boolean pii;
    private Integer imageWidth;
    private Integer imageHeight;
    private String uploaderRealm;
    private String uploaderNo;
    private String agentNo;
    private LocalDateTime boundAt;
    private LocalDateTime removedAt;
}
