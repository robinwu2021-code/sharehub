package ai.neargo.sharehub.inv.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.inv.dto.OperationDtos.Operation;

/**
 * 库存执行异常（对接明细 F04 / F05）：列出未完成的库存写操作，按步骤键向库存服务查回，原操作人原样重试。
 * 重试不换键、不重建单据：重新走一遍原业务动作，已完成的远端步骤直接复用结果。
 */
public interface InventoryOperationService {

    /** phase 空 = 未完成的（PREPARED、NEEDS_REVIEW）。 */
    PageResult<Operation> page(String phase, String kind, Integer page, Integer size);

    /**
     * 查回：结果不明、且已过在途窗口的步骤，问库存服务（领取查领取，其余查命令记录）。
     * 查到 = 远端已执行，步骤记完成（之后重试直接复用）；查不到 = 没执行，记失败。没有可恢复的步骤了就记 REJECTED。
     */
    Operation resolve(String operationNo);

    /** 定时任务用：把所有待复核操作查回一遍，返回处理条数。 */
    int resolveAll();

    /** 原样重试：只限原操作人，且仍有原动作的权限。 */
    Operation retry(String operationNo);
}
