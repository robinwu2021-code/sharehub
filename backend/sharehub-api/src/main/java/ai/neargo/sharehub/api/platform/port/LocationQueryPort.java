package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.LocationOwnership;

/**
 * 点位只读查询 —— platform 暴露给 core 的**读**面。
 *
 * <p>只读是刻意的：点位的写入口在 platform 自己的 Controller。
 * 开放写接口等于让别的服务绕过 platform 的校验改它的数据（同 {@link SiteQueryPort}）。
 */
public interface LocationQueryPort {

    /** 点位的归属链；点位不存在时返回 {@code null}。 */
    LocationOwnership ownershipOf(String locationNo);
}
