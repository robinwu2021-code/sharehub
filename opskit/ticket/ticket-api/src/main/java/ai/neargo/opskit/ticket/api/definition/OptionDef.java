package ai.neargo.opskit.ticket.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;

/**
 * 选项（字段选项、优先级、解决结果）。
 *
 * @param enabled 停用的选项不能再被新值选中，但历史值照常显示
 */
public record OptionDef(String code, LocalizedText name, boolean enabled) {
}
