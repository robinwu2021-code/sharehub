package ai.neargo.powerbank.common;

import ai.neargo.common.core.PageResult;

import java.util.List;

/**
 * 内存分页助手 → neargo {@link PageResult}（{@code {total, list}}）。
 * 供仍走内存种子的域（trade/user/platform）切片；已落库的域用 MyBatis-Plus 分页后同样构造 PageResult。
 * page 从 1 起，缺省 page=1 / size=10。
 */
public final class Pages {

    private Pages() {
    }

    public static <T> PageResult<T> of(List<T> all, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), all.size());
    }
}
