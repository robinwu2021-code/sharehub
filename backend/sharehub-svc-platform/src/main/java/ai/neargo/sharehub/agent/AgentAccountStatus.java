package ai.neargo.sharehub.agent;

import ai.neargo.sharehub.common.BizException;

/**
 * 代理登录账号状态（{@code agt_account.status}）。
 *
 * <p>取值以 **DDL 列注释为真源**（本仓库判定「哪边对」的依据）。
 * 这一列此前是裸 {@code String}，客户端传什么落什么、不报错 ——
 * 词表校验详见 {@code VocabularyRejectedOnWriteTest} 的类注释。
 *
 * <p>⚠️ 与 {@code agt_agent.status}（ENABLED/SUSPENDED，见 {@link AgentStatus}）**不是同一套**：
 * 那是代理商主体能不能做生意，这是某个登录账号能不能进来。四个值两两不同，
 * 混用的症状是「停用了他还能登进来」或者反过来。
 */
public enum AgentAccountStatus {

    /** 可登录 */
    ACTIVE,
    /** 停用，不能登录 */
    DISABLED;

    /**
     * 宽松解析：非法值抛 400。文案走 i18n key，不写中文字面量 ——
     * 非 key 的字符串会被一字不改送到客户端，界面切英文/阿语之后页面是英文、报错还是中文。
     */
    public static AgentAccountStatus of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.agent.account_status_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.agent.account_status_invalid", v);
        }
    }

    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。
     * 保存端点创建与编辑共用，没传的字段表示「不改」；用 {@link #of} 会让
     * 「只改个名字」也被拒（实测过一次，见 {@code CampaignKind.ofNullable} 的注释）。
     */
    public static AgentAccountStatus ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
