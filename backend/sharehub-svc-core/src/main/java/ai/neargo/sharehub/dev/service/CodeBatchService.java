package ai.neargo.sharehub.dev.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.dev.dto.DevDtos.CodeBatchRow;
import ai.neargo.sharehub.dev.entity.DevCodeBatch;

/**
 * 设备编码批次服务（配置类，走通用 CRUD）。
 *
 * <p>批次本身没有业务规则可言（区间 + 绑定计数），绑定进度由设备建档链路回写 {@code bound}，
 * 故不手写四件套。
 */
public interface CodeBatchService extends CrudService<DevCodeBatch, CodeBatchRow> {
}
