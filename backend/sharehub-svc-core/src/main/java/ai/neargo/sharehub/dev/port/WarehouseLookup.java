package ai.neargo.sharehub.dev.port;

/**
 * 设备域查仓库的口子（实现在 inv 包：{@code WarehouseDirectory}）。
 *
 * <p>为什么是接口而不是直接用 inv 的 Mapper：inv 已经依赖 dev（调拨要改柜子状态），
 * dev 再反过来 import inv 就成环，ArchitectureTest 的切片无环规则会拦。
 */
public interface WarehouseLookup {

    /** 仓库是否存在（未删除）。 */
    boolean exists(String warehouseNo);

    /** 校验当前操作者的仓库范围；设备旧区域字段不能代替实际仓库授权。 */
    void requireVisible(String warehouseNo);

    /** 默认回仓仓号：参数 inv.return_warehouse 配了且存在就用它，否则取第一个仓；一个仓都没有返回 null。 */
    String defaultReturnWarehouse();
}
