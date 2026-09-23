package ai.neargo.sharehub.trade.price.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.trade.price.entity.PriceAdjustment;

import java.util.Map;

/**
 * 预约调价（运营管理清单 OM-S4）。
 *
 * <p>它解决的是「节假日上调、活动期降价」这类到点改价、到期改回的需求 ——
 * 没有它，运营只能设闹钟半夜手工改方案，改完还得记得改回来。
 */
public interface PriceAdjustmentService {

    PageResult<Map<String, Object>> page(Integer page, Integer size, String keyword,
                                         String planNo, String status);

    /** 新建 / 编辑。**只有待生效的能改** —— 已生效的改了，界面上的历史就对不上账了。 */
    Map<String, Object> save(Map<String, Object> body);

    /** 撤销待生效的调价单，原因必填。 */
    Map<String, Object> cancel(String adjustNo, String reason);

    /** 提前恢复原价（不等自动恢复时刻）。 */
    Map<String, Object> revert(String adjustNo);

    /** 重试执行失败的调价单。 */
    Map<String, Object> retry(String adjustNo);

    /**
     * 把到点的调价执行掉、到期的恢复掉。**幂等**，可以被调度器和接口重复调用。
     *
     * @return 本次实际发生变化的调价单号
     */
    java.util.List<String> tick();
}
