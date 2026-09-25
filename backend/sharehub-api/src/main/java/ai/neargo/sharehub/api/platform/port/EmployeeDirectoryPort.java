package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.EmployeeBrief;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 员工目录（platform.org 实现）。系统读，豁免数据范围。 */
public interface EmployeeDirectoryPort {

    Map<String, EmployeeBrief> briefsOf(Collection<String> employeeNos);

    /** 持有给定角色码（如 OPS）的在职员工。 */
    List<EmployeeBrief> activeByRoles(Set<String> roleCodes, int limit);

    /**
     * 持有给定角色、且数据范围覆盖该区域的在职员工（「区域运维」）：范围为全部，或为区域且包含 regionId。
     * 员工级范围优先于角色级；角色是区域范围却没配具体区域的，不算覆盖（说不清管哪片）。
     */
    List<EmployeeBrief> activeInRegion(Set<String> roleCodes, String regionId, int limit);
}
