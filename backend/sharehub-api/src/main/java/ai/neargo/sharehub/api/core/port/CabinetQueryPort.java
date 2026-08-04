package ai.neargo.sharehub.api.core.port;

import ai.neargo.sharehub.api.core.dto.CabinetBrief;

import java.util.Collection;
import java.util.List;

/**
 * 机柜只读查询 —— core 暴露给 platform 的**读**面（划拨抽屉专用）。
 *
 * <p>与 {@link DeviceOwnershipPort} 分开，是因为两者的**数据范围语义相反**：
 * 那个接口的写方法必须豁免数据范围（级联要完整），本接口的所有方法必须
 * <b>保留</b>数据范围（看不到的机柜不该出现在候选池里，更不该被回收）。
 * 混在一个接口里迟早有人照着隔壁方法的写法把 scope 关掉。
 *
 * <p>只读是刻意的：归属的唯一写入口是 {@link DeviceOwnershipPort}。
 */
public interface CabinetQueryPort {

    /**
     * {@code agentNo} 传这个哨兵值 = 只看平台直营。
     *
     * <p>用哨兵而非空串：空串与「不限」在 HTTP query string 里无法区分，
     * 远程实现会把「只看直营」悄悄降级成「全都要」—— 那是最难发现的一类差异，
     * 单体形态下过滤正确、拆分后过滤失效，两边都不报错。
     */
    String DIRECT_OPERATED = "__NONE__";

    /**
     * 可划拨机柜候选池（带当前归属，供 UI 标注「已属某代理」避免误划）。
     *
     * @param keyword 机柜编号/名称模糊匹配，可为 null
     * @param agentNo 只看某代理名下的；传 {@code "__NONE__"} 表示只看平台直营，null 表示不限
     * @param limit   条数上限，由实现方钳制
     */
    List<CabinetBrief> assignable(String keyword, String agentNo, Integer limit);

    /**
     * 批量取机柜当前归属 —— 回收时反查「从谁手里收回来的」。
     *
     * <p>批量而非单个：回收抽屉一次提交 N 台，单个接口会诱导调用方写出 N+1。
     */
    List<CabinetBrief> briefsByNos(Collection<String> cabinetNos);
}
