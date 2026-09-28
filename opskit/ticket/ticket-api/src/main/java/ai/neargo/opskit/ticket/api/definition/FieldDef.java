package ai.neargo.opskit.ticket.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;

/**
 * 字段定义（21/02 §七）。
 *
 * @param onCreate   建单时可填（否则只能在后续编辑里填）
 * @param filterable 值写入索引表，列表可按它筛选 / 排序
 * @param options    SELECT / MULTI_SELECT 必填
 * @param multiple   MEMBER 是否多值
 * @param refType    REF 限定的外部引用类型（空 = 不限）
 */
public record FieldDef(String code, LocalizedText name, FieldType type, boolean required, boolean onCreate, boolean filterable, Options options,
        boolean multiple, String refType) {
}
