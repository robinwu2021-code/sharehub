package ai.neargo.sharehub.user.marketing;

import ai.neargo.sharehub.common.BizException;

/**
 * 推送触达渠道（{@code mkt_push.channel}）。
 *
 * <p>取值以 **DDL 列注释为真源**（本仓库判定「哪边对」的依据）。
 * 这一列此前是裸 {@code String}，客户端传什么落什么、不报错 ——
 * 词表校验详见 {@code VocabularyRejectedOnWriteTest} 的类注释。
 *
 * <p>⚠️ **没有 SMS**。运营端曾把 {@code SMS} 也放进渠道下拉，而后端
 * 从不按渠道分支、全仓没有任何一处给推送发短信 —— 选了就落库，然后什么都不发生。
 * 建本枚举时按 DDL 词表（两档）定，并撤掉了前端那个选项（先例 {@code 5e8574c}：
 * 代理数据范围那个选了不生效的表单项）。要做短信推送是加功能，不是补一个下拉项。
 */
public enum PushChannel {

    /** App 推送 */
    APP_PUSH,
    /** 订阅消息（站内） */
    SUBSCRIBE;

    /**
     * 宽松解析：非法值抛 400。文案走 i18n key，不写中文字面量 ——
     * 非 key 的字符串会被一字不改送到客户端，界面切英文/阿语之后页面是英文、报错还是中文。
     */
    public static PushChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.push.channel_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.push.channel_invalid", v);
        }
    }

    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。
     * 保存端点创建与编辑共用，没传的字段表示「不改」；用 {@link #of} 会让
     * 「只改个名字」也被拒（实测过一次，见 {@code CampaignKind.ofNullable} 的注释）。
     */
    public static PushChannel ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
