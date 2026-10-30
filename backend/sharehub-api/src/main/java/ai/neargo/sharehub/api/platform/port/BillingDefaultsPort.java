package ai.neargo.sharehub.api.platform.port;

/**
 * 计费默认值读取（platform.sys 实现，数据在「系统设置 › 计费默认」{@code sys_biz_rule}）。
 *
 * <p>为什么要这个端口：最长租赁时限 {@code overdueHours} 在系统设置页能配，而读它的逾期任务在交易域 ——
 * 交易域不依赖 platform，此前没有任何代码读这个值，页面上配了等于没配（缺陷 D8）。
 */
public interface BillingDefaultsPort {

    /** 最长租赁时限（小时）；没配返回 {@code null}。 */
    Integer overdueHours();
}
