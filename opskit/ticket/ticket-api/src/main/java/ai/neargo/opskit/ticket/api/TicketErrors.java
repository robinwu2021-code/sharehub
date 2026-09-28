package ai.neargo.opskit.ticket.api;

import ai.neargo.opskit.commons.error.ErrorKey;

/** 工单模块错误键（21/06 §九）。通用的在 CommonErrors。 */
public final class TicketErrors {

    public static final ErrorKey FIELD_INVALID = new ErrorKey("opskit.ticket.field_invalid", 422);
    public static final ErrorKey TYPE_DISABLED = new ErrorKey("opskit.ticket.type_disabled", 422);
    public static final ErrorKey FILTER_TOO_BROAD = new ErrorKey("opskit.ticket.filter_too_broad", 422);
    public static final ErrorKey MERGE_INTO_SELF = new ErrorKey("opskit.ticket.merge_into_self", 422);
    public static final ErrorKey TICKET_CLOSED = new ErrorKey("opskit.ticket.closed", 422);

    private TicketErrors() {
    }
}
