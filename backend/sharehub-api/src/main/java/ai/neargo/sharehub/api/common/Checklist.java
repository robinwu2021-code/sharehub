package ai.neargo.sharehub.api.common;

import java.util.List;

/**
 * 门禁 / 前置清单（站点开业清单、站点关闭门禁、设备上线门禁）。前端渲染成 GateChecklist。
 *
 * <p>为什么单独一个只读端点返回它：拒绝时的 409 响应体只有 code/message、带不了清单。
 * 前端先读清单、全部通过才启用动作按钮；动作端点在服务端<b>重新校验一遍</b>（防并发与绕过前端）。
 */
public record Checklist(boolean allPassed, List<Item> items) {

    /** @param fixHref 未通过时「去处理」的运营端链接，可空 */
    public record Item(String key, String label, boolean passed, String detail, String fixHref) {
    }

    public static Checklist of(List<Item> items) {
        return new Checklist(items.stream().allMatch(Item::passed), items);
    }

    /** 未通过项的 key，逗号分隔（动作端点 409 的 i18n 参数；中文标签不进报错，界面按 key 翻译并引导去看清单）。 */
    public String firstFailedKey() {
        return items.stream().filter(i -> !i.passed()).map(Item::key).collect(java.util.stream.Collectors.joining(", "));
    }

    /** 第一项未通过的说明（日志用）。 */
    public String firstFailure() {
        return items.stream().filter(i -> !i.passed()).map(i -> i.label() + "：" + i.detail()).findFirst().orElse(null);
    }
}
