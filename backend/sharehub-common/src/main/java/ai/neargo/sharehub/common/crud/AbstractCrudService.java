package ai.neargo.sharehub.common.crud;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 字典/配置类实体的 CRUD 基类。子类只需给出「表的业务键是哪列、怎么转 VO」，
 * 分页 / 关键词 / 等值筛选 / 取号 / upsert / 软删全部由本类兜底。
 *
 * <p><b>为什么用字符串列名而不是 {@code LambdaQueryWrapper}</b>：泛型 {@code E} 下
 * {@code SFunction<E,?>} 无法在基类里安全地引用 {@code BaseEntity} 的列（如默认排序用的 {@code id}），
 * 会逼子类把每个查询条件都重新声明一遍，基类就失去意义。字典表的列名稳定、改动极少，
 * 用字符串换取「子类只写十几行」是划算的。**有业务规则的聚合根仍走 LambdaQueryWrapper 手写**，
 * 保留编译期列名安全。
 *
 * <p><b>取号</b>：按 [db-design §1.4.1] 的约定「扫描同前缀已有号取 max+1」，
 * <b>禁止</b> {@code 前缀 + 数组长度}（该写法在前端 mock 已导致过两次主键撞号）。
 * 并发下仍可能撞号，靠业务键 UNIQUE 兜底 —— 调用方需处理 {@code DuplicateKeyException} 重试。
 *
 * @param <E> 实体
 * @param <V> 出参 VO
 */
public abstract class AbstractCrudService<E extends BaseEntity, V> implements CrudService<E, V> {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    protected static final String TENANT_MAIN = "MAIN";

    protected final BaseMapper<E> mapper;

    protected AbstractCrudService(BaseMapper<E> mapper) {
        this.mapper = mapper;
    }

    // ——————————————————————— 子类必须给出 ———————————————————————

    /** 实体 → 出参 VO。 */
    protected abstract V toVO(E e);

    /** 业务键的**下划线列名**，如 {@code "bank_code"}。 */
    protected abstract String keyColumn();

    /** 读实体的业务键值。 */
    protected abstract String keyOf(E e);

    /** 写实体的业务键值（新建取号后回填）。 */
    protected abstract void setKey(E e, String no);

    // ——————————————————————— 子类可选覆盖 ———————————————————————

    /**
     * 业务键前缀（如 {@code "BK"}）。返回 {@code null} 表示**自然键**
     * （{@code vendor_code}/{@code country_code}/{@code param_key} 等对外有语义的键），
     * 此时新建必须由调用方显式给键，本类不代为取号。
     */
    protected String keyPrefix() {
        return null;
    }

    /** 取号时数字部分的补零宽度。 */
    protected int keyWidth() {
        return 4;
    }

    /** 关键词模糊匹配的列（下划线列名）。默认只匹配业务键。 */
    protected String[] keywordColumns() {
        return new String[]{keyColumn()};
    }

    /** 允许等值筛选的字段白名单（**驼峰**，如 {@code "status"}/{@code "country"}）。默认不允许任何筛选。 */
    protected String[] filterFields() {
        return new String[0];
    }

    /** 默认排序列（下划线）。 */
    protected String orderColumn() {
        return "id";
    }

    /** 默认降序。 */
    protected boolean orderDesc() {
        return true;
    }

    /** 新建前的钩子（补默认值 / 校验）。 */
    protected void beforeCreate(E e) {
    }

    /** 更新前的钩子（保护不可改字段）。 */
    protected void beforeUpdate(E e, E current) {
    }

    // ——————————————————————— 实现 ———————————————————————

    @Override
    public PageResult<V> page(Integer page, Integer size, String keyword, Map<String, String> filters) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        QueryWrapper<E> w = new QueryWrapper<>();
        applyKeyword(w, keyword);
        applyFilters(w, filters);
        applyArchived(w, filters);
        w.orderBy(true, !orderDesc(), orderColumn());

        Page<E> r = mapper.selectPage(new Page<>(p, s), w);
        List<V> rows = r.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public V get(String no) {
        E e = selectByKey(no);
        return e == null ? null : toVO(e);
    }

    @Override
    public V save(E body) {
        String no = keyOf(body);

        if (no == null || no.isBlank()) {
            String prefix = keyPrefix();
            if (prefix == null) {
                throw new IllegalArgumentException(keyColumn() + " 是自然键，新建时必须显式提供");
            }
            setKey(body, nextNo(prefix));
            body.setTenantId(TENANT_MAIN);
            beforeCreate(body);
            mapper.insert(body);
            return toVO(selectByKey(keyOf(body)));
        }

        E current = selectByKey(no);
        if (current == null) {
            if (body.getTenantId() == null) body.setTenantId(TENANT_MAIN);
            beforeCreate(body);
            mapper.insert(body);
        } else {
            body.setId(current.getId());
            body.setVersion(current.getVersion());
            if (body.getTenantId() == null) body.setTenantId(current.getTenantId());
            beforeUpdate(body, current);
            mapper.updateById(body);
        }
        return toVO(selectByKey(no));
    }

    /**
     * 归档 / 取消归档的统一实现。
     *
     * <p>实体实现 {@link Archivable} 即自动获得能力 —— **15 个可归档资源共用这一份**，
     * 不必各写一遍。子类无需覆盖。
     *
     * <p>幂等：重复归档只是重新盖时间戳，不报错。运营点两次不该失败。
     */
    @Override
    public V archive(String no) {
        return setArchived(no, LocalDateTime.now());
    }

    @Override
    public V unarchive(String no) {
        return setArchived(no, null);
    }

    private V setArchived(String no, LocalDateTime at) {
        E e = selectByKey(no);
        if (e == null) {
            throw new IllegalArgumentException("记录不存在: " + no);
        }
        if (!(e instanceof Archivable a)) {
            throw new UnsupportedOperationException(
                    e.getClass().getSimpleName() + " 未实现 Archivable，不支持归档");
        }
        a.setArchivedAt(at);
        mapper.updateById(e);
        return toVO(selectByKey(no));
    }

    /**
     * 列表是否默认过滤已归档。
     *
     * <p>默认 true —— 归档的东西不该出现在日常列表里，否则归档就没意义了。
     * 前端传 {@code showArchived=true} 可查看全部（见 {@link #page}）。
     */
    protected boolean filterArchivedByDefault() {
        return true;
    }

    @Override
    public boolean remove(String no) {
        E e = selectByKey(no);
        if (e == null) return false;
        return mapper.deleteById(e.getId()) > 0; // @TableLogic → 软删
    }

    // ——————————————————————— 内部 ———————————————————————

    protected E selectByKey(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<E>().eq(keyColumn(), no).last("limit 1"));
    }

    /** 扫描同前缀最大号 + 1。见类注释关于并发的说明。 */
    protected String nextNo(String prefix) {
        // 数字序取最大 —— **不能用字典序**：位宽不一致时会取错。
        // 实测（提现单）：库里同时有 WD9002(4位) 与 WD009003(6位)，字典序下 'WD9002' > 'WD009003'，
        // 取到 9002 → 下一个算成 WD009003 → 撞已存在行报 Duplicate entry。
        List<Object> top = mapper.selectObjs(new QueryWrapper<E>()
                .select("MAX(CAST(SUBSTRING(" + keyColumn() + ", " + (prefix.length() + 1)
                        + ") AS UNSIGNED)) AS mx")
                .likeRight(keyColumn(), prefix));

        long n = 0L;
        if (top != null && !top.isEmpty() && top.get(0) != null) {
            try {
                n = Long.parseLong(String.valueOf(top.get(0)));
            } catch (NumberFormatException ignore) {
                // 全是脏号时从 0 起，由业务键 UNIQUE 兜底
            }
        }
        return prefix + String.format("%0" + keyWidth() + "d", n + 1);
    }

    /**
     * 默认只列未归档。
     *
     * <p>**用列存在与否判断而不是实体类型**：`page()` 是泛型方法，此处拿不到实体实例，
     * 无法 `instanceof Archivable`。改为「子类声明了归档列就过滤」。
     */
    private void applyArchived(QueryWrapper<E> w, Map<String, String> filters) {
        if (!filterArchivedByDefault() || !hasArchivedColumn()) return;
        String show = filters == null ? null : filters.get("showArchived");
        boolean showAll = "true".equalsIgnoreCase(show) || "1".equals(show);
        if (!showAll) {
            w.isNull("archived_at");
        }
    }

    /** 子类若对应的表有 {@code archived_at} 列则覆盖为 true。 */
    protected boolean hasArchivedColumn() {
        return false;
    }

    private void applyKeyword(QueryWrapper<E> w, String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        String kw = keyword.trim();
        String[] cols = keywordColumns();
        if (cols.length == 0) return;
        w.and(q -> {
            for (int i = 0; i < cols.length; i++) {
                if (i == 0) q.like(cols[i], kw);
                else q.or().like(cols[i], kw);
            }
        });
    }

    private void applyFilters(QueryWrapper<E> w, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) return;
        for (String field : filterFields()) {
            String v = filters.get(field);
            if (v != null && !v.isBlank()) {
                w.eq(camelToSnake(field), v);
            }
        }
    }

    /** 驼峰字段名 → 下划线列名。仅用于 {@link #filterFields()} 白名单内的值，不接受任意输入（防注入）。 */
    protected static String camelToSnake(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
