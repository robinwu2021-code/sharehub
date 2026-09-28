package ai.neargo.opskit.ticket.api.model;

/** @param attached true = 去重命中，没有新建，附加到了既有未结单（返回的是那张单） */
public record CreateTicketResult(TicketView ticket, boolean attached) {
}
