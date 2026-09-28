package ai.neargo.sharehub.platform.md;

/**
 * 问题条目的上架状态（{@code md_problem.status}，DDL 注释 {@code 'ENABLED/DISABLED'}）。
 *
 * <p>C 端 FAQ 是**按 {@code status='ENABLED'} 筛**出来的（{@code ProblemServiceImpl#faq}）——
 * 所以这一列写错的症状不是报错，是**这条问题从用户那边消失**，
 * 而运营端列表里它好好地在，两边都不报错。
 *
 * <p>⚠️ 词表是 ENABLED/DISABLED，不是别处常见的 ACTIVE/INACTIVE。
 * 本仓库同时存在三套「开关」词表（见 {@code DdlStatusVocabularyCommentTest}），
 * 挑错一套的后果与写错一个字母相同。
 */
public enum ProblemStatus {

    /** 上架，C 端 FAQ 可见（DDL 默认值）。 */
    ENABLED,
    /** 下架：运营端仍可编辑，C 端查不到。 */
    DISABLED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ProblemStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("问题状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("问题状态非法: " + v + "（仅 ENABLED/DISABLED）");
        }
    }
}
