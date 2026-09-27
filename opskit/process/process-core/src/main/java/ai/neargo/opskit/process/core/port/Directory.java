package ai.neargo.opskit.process.core.port;

import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;

/**
 * 流程需要的目录信息（组员、队列的领取超时）。真源在平台层；按分层规则流程不能依赖平台 core，
 * 由启动模块把平台的实现接进来（21/02 §二「只经客户端接口」）。
 */
public interface Directory {

    /** 组的当前成员（会签快照、轮转派单用）。 */
    List<String> membersOf(TenantContext t, String groupCode);

    /** 队列组的领取超时；不是队列组或未配置时为空。 */
    Optional<Integer> claimTimeoutSec(TenantContext t, String groupCode);
}
