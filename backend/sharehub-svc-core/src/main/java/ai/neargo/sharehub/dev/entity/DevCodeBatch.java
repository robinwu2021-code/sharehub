package ai.neargo.sharehub.dev.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备编码批次（dev_code_batch，[db-design §3.1]）。业务键前缀 {@code BC}（{@link ai.neargo.sharehub.common.BizKey#CODE_BATCH}）。
 *
 * <p>配置类实体：按「批次 + 供应商」归集出厂编码区间，并跟踪绑定进度（{@code bound}/{@code total}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_code_batch")
public class DevCodeBatch extends BaseEntity {

    private String batchNo;

    private String vendorCode;

    /** QR（二维码）/ SN（出厂序列号）。 */
    private String codeType;

    private String rangeStart;

    private String rangeEnd;

    /** 批次总量。 */
    private Integer total;

    /** 已绑定数量（列表渲染 已绑定/总数 进度条）。 */
    private Integer bound;

    /** 生产日期（DATE，仅日期）。 */
    private String producedAt;

    /** PENDING / PARTIAL / BOUND / VOID。 */
    private String status;
}
