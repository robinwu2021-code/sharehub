package ai.neargo.sharehub.api.platform.port;

/**
 * 系统参数写入前由所属领域把关（platform 实现参数存取，不懂各领域的切换规则）。
 *
 * <p>只在取值真的变化时调用，且在按字典校验、归一之后。拒绝就抛业务异常，参数不落库。
 * 例：库存引擎 {@code inv.engine} 切换前要求库存服务可达、没有未完成的库存操作。
 */
public interface SysParamWriteGuard {

    /** 只对自己关心的参数键返回 true。 */
    boolean handles(String paramKey);

    /** @param oldValue 现值，新建参数时为空；@param newValue 已归一的新值 */
    void check(String paramKey, String oldValue, String newValue);
}
