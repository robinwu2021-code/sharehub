package ai.neargo.sharehub.dev.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备信号流水（dev_signal_log，追加，V109）：窗口计数型告警的输入。心跳不入此表，只入「掉线后恢复」。 */
@Data
@TableName("dev_signal_log")
public class DevSignalLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String cabinetNo;
    private String code;
    private Integer slotIndex;
    private String powerbankNo;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
