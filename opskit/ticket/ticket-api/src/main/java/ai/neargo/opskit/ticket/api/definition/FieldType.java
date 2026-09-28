package ai.neargo.opskit.ticket.api.definition;

/**
 * 字段类型（21/01 TKT-02，11 种）。值的线上形状：
 * <ul>
 *   <li>TEXT（≤ 255）· LONG_TEXT（≤ 20000）：字符串</li>
 *   <li>NUMBER：数字 · MONEY：{@code {amount: 最小单位整数, currency: ISO 4217}}</li>
 *   <li>DATETIME：毫秒</li>
 *   <li>SELECT：选项代号 · MULTI_SELECT：选项代号列表</li>
 *   <li>MEMBER：{@code member:x}（{@code multiple} 时为列表）· GROUP：组代号</li>
 *   <li>ATTACHMENT：{@code [{id, name, size?, contentType?}]}（只存引用，文件在接入方）</li>
 *   <li>REF：{@code {type, ref, display?}}（外部引用：业务对象 + 快照）</li>
 * </ul>
 */
public enum FieldType {
    TEXT, LONG_TEXT, NUMBER, MONEY, DATETIME, SELECT, MULTI_SELECT, MEMBER, GROUP, ATTACHMENT, REF;

    public boolean hasOptions() {
        return this == SELECT || this == MULTI_SELECT;
    }
}
