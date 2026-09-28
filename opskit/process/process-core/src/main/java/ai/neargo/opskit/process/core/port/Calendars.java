package ai.neargo.opskit.process.core.port;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.platform.api.calendar.BusinessCalendar;
import java.util.Optional;

/** SLA 按工作日历计时用（真源在平台层，由启动模块接进来；未配置的日历为空 = 退回自然时间并记警告）。 */
public interface Calendars {

    Optional<BusinessCalendar> find(TenantContext t, String code);

    /** 不接日历（测试、或只用 7×24 的部署）。 */
    Calendars NONE = (t, code) -> Optional.empty();
}
