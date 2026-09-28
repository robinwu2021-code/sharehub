package ai.neargo.opskit.commons.query;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import java.util.List;
import java.util.Map;

/**
 * 分页参数（21/06 §二）：page 从 1 起，size ≤ 200。越界一律 422 而不是悄悄截断 ——
 * 把分页当「取全部」用、越界静默截断，是运营系统里反复出现的缺陷。
 */
public record PageRequest(int page, int size) {

    public static final int MAX_SIZE = 200;

    public static PageRequest of(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 20 : size;
        if (p < 1) {
            throw new OpsKitException(CommonErrors.VALIDATION, "page starts at 1", Map.of(), List.of(new FieldError("page", "opskit.page.out_of_range")));
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new OpsKitException(CommonErrors.VALIDATION, "size must be 1.." + MAX_SIZE, Map.of("max", MAX_SIZE),
                    List.of(new FieldError("size", "opskit.page.size_out_of_range")));
        }
        return new PageRequest(p, s);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
