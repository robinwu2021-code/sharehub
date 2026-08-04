package ai.neargo.sharehub.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankCmd;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankRow;

/**
 * 充电宝服务（聚合根，手写）—— 生命周期由 {@link ai.neargo.sharehub.dev.PowerbankStateMachine} 把关，
 * 故不走 {@code AbstractCrudService}（那套的 upsert 会绕过状态机直接覆盖 status）。
 */
public interface PowerbankService {

    /** 分页：keyword 匹配 powerbank_no/sn；status/cabinetNo 等值筛选。 */
    PageResult<PowerbankRow> page(Integer page, Integer size, String keyword, String status, String cabinetNo);

    /** 按业务键单查；不存在返回 {@code null}。 */
    PowerbankRow get(String powerbankNo);

    /** 建档（入库）：取号 {@code PB*}，初始态 {@code IN_STOCK}。 */
    PowerbankRow create(PowerbankCmd cmd);

    /** 更新：属性字段直改；状态变更必须经状态机（{@code event} 或目标 {@code status} 二选一）。 */
    PowerbankRow update(String powerbankNo, PowerbankCmd cmd);

    /** 归档充电宝：盖 archivedAt 时间戳。**不是删除**，可 unarchive 恢复。 */
    PowerbankRow archive(String no);

    /** 取消归档充电宝：清空时间戳，回到默认列表。 */
    PowerbankRow unarchive(String no);
}
