package ai.neargo.sharehub.platform.notify;

import java.util.Arrays;
import java.util.Optional;

/**
 * 模板启用与否（{@code notify_template.status}，词表见 V65 的列注释 {@code ENABLED/DISABLED}）。
 *
 * <h3>这一列的默认值曾经不在词表里</h3>
 * 建表时给的是 {@code DEFAULT 'ACTIVE'} —— 而服务建模板写的是 {@code ENABLED}、
 * 运营端 {@code NotifyTemplate.status} 也只有 {@code ENABLED/DISABLED}。
 * 走服务建的行没事，<b>所有不走服务的插入路径</b>（种子 SQL、数据导入、手工补行）
 * 落进去的都是一个两端都不认识的值：徽标映射不上、按状态筛一条都查不到，且不报错。
 * V65 把默认值改成 {@code ENABLED} 并补上了列注释 —— 当初没有注释，
 * 就没有任何一处能说那个 {@code ACTIVE} 不对。
 */
public enum NotifyTemplateStatus {

    /** 启用中，可被 {@code /internal/platform/notify/send} 取用。 */
    ENABLED,
    /** 停用。停用不是删除 —— 历史 {@code notify_log.template_no} 仍然指着它。 */
    DISABLED;

    public static Optional<NotifyTemplateStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
