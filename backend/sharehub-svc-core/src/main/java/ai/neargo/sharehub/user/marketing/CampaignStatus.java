package ai.neargo.sharehub.user.marketing;

/**
 * 营销活动状态（{@code mkt_campaign.status}）—— {@link CampaignStateMachine} 的取值域。
 *
 * <p><b>建这个枚举时发现 DDL 注释漏了 {@link #PAUSED}</b>（原注释写 {@code DRAFT/RUNNING/ENDED}），
 * 而状态机的 {@code pause} 边确实会把 {@code PAUSED} 写进这一列。
 * 症状是延迟发作的：库里一天没有暂停过的活动，{@code StoredValueInVocabularyTest} 就一天是绿的；
 * 等真有人点了「暂停」，那一行的状态就<b>不在这一列自己声明的词表里</b> —— 而 DDL 列注释
 * 正是本仓库判定「哪边是对的」的依据，依据本身错了，两端对不上时就没有裁判。
 * 已由 {@code V80__campaign_status_vocabulary.sql} 补正。
 *
 * <p>与运营端 {@code ops-web/lib/types/marketing.ts} 的 {@code CampaignStatus} 一字不差。
 */
public enum CampaignStatus {

    /** 草稿，未启动。 */
    DRAFT,
    /** 进行中。 */
    RUNNING,
    /** 已暂停 —— <b>不是终态</b>，可再启动。 */
    PAUSED,
    /** 已结束 —— 终态，不在任何迁移的 {@code to} 之外，也不在任何 {@code from} 里。 */
    ENDED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CampaignStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("活动状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("活动状态非法: " + v + "（仅 DRAFT/RUNNING/PAUSED/ENDED）");
        }
    }
}
