package ai.neargo.opskit.platform.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * 产品权限目录（21/01 §九）。目录由产品固定，租户只能把它们组合成角色；
 * 宿主自己的权限码经令牌声明映射进角色，不进这个目录。
 */
public enum Permission {
    TICKET_CREATE("ticket:create"),
    TICKET_VIEW_OWN("ticket:view:own"),
    TICKET_VIEW_SCOPED("ticket:view:scoped"),
    TICKET_VIEW_ALL("ticket:view:all"),
    TICKET_WORK("ticket:work"),
    TICKET_ASSIGN("ticket:assign"),
    TICKET_ESCALATE("ticket:escalate"),
    TICKET_MERGE("ticket:merge"),
    TICKET_COMMENT_INTERNAL("ticket:comment:internal"),
    APPROVAL_SUBMIT("approval:submit"),
    APPROVAL_DECIDE("approval:decide"),
    APPROVAL_VIEW_ALL("approval:view:all"),
    PROCESS_MANAGE("process:manage"),
    TICKET_TYPE_MANAGE("ticket-type:manage"),
    SLA_MANAGE("sla:manage"),
    DIRECTORY_MANAGE("directory:manage"),
    CALENDAR_MANAGE("calendar:manage"),
    INTEGRATION_MANAGE("integration:manage"),
    REPORT_VIEW("report:view");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Permission> ofCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
