package ai.neargo.opskit.ticket.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.List;

/** 选项集（可被多个工单类型、多个字段引用）；列表顺序即序号（优先级排序用）。 */
public record OptionSetDef(String code, LocalizedText name, List<OptionDef> options) {
    public OptionSetDef {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
