package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.model.SubjectRef;

/** 实例行（实例查询、对账）。名称是多语种原文，由接口层按语言选。 */
public record InstanceRow(String instanceNo, String processCode, LocalizedText processName, String processVersion, SubjectRef subject, String status,
        LocalizedText statusName, String statusCategory, boolean open, String createdBy, long createdAtMs, long updatedAtMs, Long closedAtMs) {
}
