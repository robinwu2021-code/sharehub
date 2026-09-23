package ai.neargo.sharehub.dev.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CabinetDetail;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.CommandResult;

import java.util.Map;

/** 设备业务：柜机列表/详情(含派生仓位)/远程指令(转 access-gateway 占位)。 */
public interface CabinetService {
    PageResult<Cabinet> page(Integer page, Integer size, String keyword, String onlineStatus, String status);
    CabinetDetail detail(String cabinetNo);

    /** 某站点下全部在用机柜（C 端找柜/门店详情）。 */
    java.util.List<Cabinet> bySite(String siteNo);
    CommandResult sendCommand(String cabinetNo, String type, Map<String, Object> params);

    /**
     * 机柜建档 / 编辑（upsert）。
     *
     * <p><b>为什么补这个</b>：此前只有批量导入 `importRows`，**没有单台新建的入口** ——
     * 前端契约里有 `saveCabinet`，后端连路径都没有，于是「上一台新设备」只能靠往库里灌数据。
     * 这是 MVP ④「上设备并绑定归属」那一环的缺口。
     *
     * <p><b>归属随点位级联</b>：`siteNo` / `agentNo` 不接受调用方直接指定，
     * 一律由 `locationNo` 反查得出 —— 三者各填各的必然互相矛盾，
     * 而矛盾之后「这台柜子归谁」就没有答案了（同 {@code AssetAssignedListener} 的级联口径）。
     *
     * @param cabinetNo 为空表示新建；非空表示编辑该台
     */
    Cabinet save(String cabinetNo, Map<String, Object> body);

    /** 归档机柜：盖 archivedAt 时间戳。**不是删除**，可 unarchive 恢复。 */
    Cabinet archive(String no);

    /** 取消归档机柜：清空时间戳，回到默认列表。 */
    Cabinet unarchive(String no);

    /**
     * 批量导入机柜。
     *
     * <p><b>逐行校验、整批回滚</b>：一行有问题就整批拒绝并指出是第几行 ——
     * 部分成功会让运营不知道该重传全部还是补传剩余，而重传已成功的行会撞唯一键。
     *
     * @return 导入成功的行数
     */
    int importRows(java.util.List<java.util.Map<String, Object>> rows);
}
