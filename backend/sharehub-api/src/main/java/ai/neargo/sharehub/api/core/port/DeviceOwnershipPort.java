package ai.neargo.sharehub.api.core.port;

import java.util.List;

/**
 * 设备归属回写 —— core 暴露给 platform 的**写**面（划拨级联专用）。
 *
 * <p><b>为什么是「写」而不是查询</b>：代理商划拨发生在 platform（`agt_assignment`），
 * 但机柜的 `agent_no` 归 core 所有（ADR-018 表归属）。platform 不能直接改 core 的表，
 * 只能请 core 自己改 —— 这个接口就是那个请求。
 *
 * <p><b>接口很窄是刻意的</b>：只有「按点位批量改归属」这一个动作，
 * 不提供通用的机柜更新。开放通用写接口等于把 core 的表交给别人改，
 * 那和直连 mapper 没有区别，只是多了一层。
 *
 * <p><b>⚠️ 这个接口目前仍在调用方的事务里同步执行</b> —— 它解决的是「不直连别人的表」
 * （ADR-017 纪律三），**没有解决跨服务事务**（纪律二）。拆分前必须改为
 * 事件驱动最终一致：划拨落单发事件 → core 订阅后自行回写 + 对账补偿。
 * 见 ADR-017「三处必须先解决的」第 1 项。
 */
public interface DeviceOwnershipPort {

    /**
     * 把指定点位下的机柜归属改到 {@code agentNo}，并同步冗余的 {@code siteNo}。
     *
     * <p><b>不受数据范围过滤</b>：级联必须完整。若被 scope 过滤会产生
     * 「站点改了但部分机柜没改」的部分级联，比不划拨更糟 —— 授权闸门在调用方的
     * 存在性校验里，不在这里。
     *
     * @return 实际更新行数
     */
    int reassignByLocations(List<String> locationNos, String agentNo, String siteNo);

    /** 单台机柜改归属。 */
    int reassignCabinet(String cabinetNo, String agentNo);

    /**
     * 带数据范围的机柜存在性检查 —— **授权闸门**，与上面两个写方法的语义正好相反。
     *
     * <p>写方法必须豁免数据范围（级联要完整），本方法必须**保留**数据范围
     * （看不到的对象不该被划拨）。两者放在同一个接口里，是因为它们服务于同一个动作，
     * 但**注释必须写清差异** —— 把它们的 scope 语义搞反，一个会造成部分级联，
     * 另一个会造成越权划拨。
     */
    boolean existsInScope(String cabinetNo);
}
