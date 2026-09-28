package ai.neargo.opskit.commons.query;

import java.util.List;
import java.util.function.Function;

/** 列表结果 {@code {list, total, page, size}}（21/06 §二）。 */
public record Page<T>(List<T> list, long total, int page, int size) {

    public Page {
        list = List.copyOf(list);
    }

    public static <T> Page<T> of(List<T> list, long total, PageRequest req) {
        return new Page<>(list, total, req.page(), req.size());
    }

    public static <T> Page<T> empty(PageRequest req) {
        return new Page<>(List.of(), 0, req.page(), req.size());
    }

    public <R> Page<R> map(Function<? super T, ? extends R> f) {
        return new Page<>(list.stream().<R>map(f).toList(), total, page, size);
    }
}
