package ai.neargo.sharehub.api.platform.dto;

/** 员工摘要（跨模块）：运维责任人校验、工单派单候选人。 */
public record EmployeeBrief(String employeeNo, String name, String status, String roleCode) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
