package ai.neargo.sharehub.common.crud;

import ai.neargo.common.core.PageResult;

import java.util.Map;

/**
 * 通用 CRUD 契约：分页查 / 单查 / upsert / 软删。
 *
 * <p>适用对象：**字典与配置类实体**（银行/问题/地区/版本/税率/市场/参数…约 40 张表）——
 * 它们的读写形态高度一致，逐个手写四件套只会产出重复模板。有真实业务规则的聚合根
 * （订单/工单/告警/退款…）**不要**继承这套，仍按 {@code wo}/{@code dev} 域的手写方式实现。
 *
 * <p>与 ops-web 契约的对应：{@code list*} → {@link #page}，{@code save*} → {@link #save}。
 * 全站零 DELETE（[api/README §6A.3]），{@link #remove} 是 {@code @TableLogic} 软删，不是物理删。
 *
 * @param <E> 实体类型（继承 {@code BaseEntity}）
 * @param <V> 出参 VO 类型
 */
public interface CrudService<E, V> {

    /**
     * 分页查询。
     *
     * @param keyword 关键词，匹配列由实现方 {@code keywordColumns()} 声明；空则不过滤
     * @param filters 等值筛选，键为**驼峰字段名**（如 {@code country}/{@code status}），实现方转下划线列名
     */
    PageResult<V> page(Integer page, Integer size, String keyword, Map<String, String> filters);

    /** 按业务键单查；不存在返回 {@code null}。 */
    V get(String no);

    /** upsert：业务键为空则新建（自动取号），否则按业务键更新。 */
    V save(E body);

    /** 软删（逻辑删除）。返回是否命中。 */
    boolean remove(String no);

    /**
     * 归档：盖当前时间戳。**不是删除** —— 行仍在，运营端勾「显示已归档」可见，可恢复。
     *
     * @return 归档后的 VO；实体不支持归档时抛 {@link UnsupportedOperationException}
     */
    default V archive(String no) {
        throw new UnsupportedOperationException("该资源不支持归档");
    }

    /** 取消归档：清空时间戳，回到默认列表。 */
    default V unarchive(String no) {
        throw new UnsupportedOperationException("该资源不支持归档");
    }
}
