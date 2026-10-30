package ai.neargo.sharehub.cs;

import ai.neargo.sharehub.common.BizException;

/**
 * 客服受理单的来源渠道（{@code cs_ticket.channel}，DDL 注释
 * {@code 'APP/MP/H5/PHONE/EMAIL/MANUAL/ALARM'}）。
 *
 * <h2>这一列没校验过，而它已经造成过一次「卡口被脏值关掉」</h2>
 * 手工建单原先是 {@code e.setChannel(req.channel().trim())} —— 传什么存什么。
 * {@code CsTicketCreateTest} 因此往库里写进了一行 {@code channel='电话'}
 * （中文，不在词表里），而它一直绿着。
 *
 * <p>真正的代价不在那一行本身：{@code StoredValueInVocabularyTest} 按
 * 「这一列的取值是不是全都是大写形」判该不该查一列 ——
 * <b>那一个中文值进去之后，整个 {@code cs_ticket.channel} 列从那条卡口的视野里消失了</b>，
 * 而它依旧显示为绿。<b>唯一能发现脏数据的卡口，被脏数据本身关掉了。</b>
 * （堵这个洞的是同类里新加的那条「混形列」用例。）
 */
public enum CsTicketChannel {

    /** C 端 App。 */
    APP,
    /** 小程序。 */
    MP,
    /** H5。 */
    H5,
    /** 来电（客服代客登记）。 */
    PHONE,
    /** 邮件。 */
    EMAIL,
    /** 运营手工登记（DDL 默认值口径）。 */
    MANUAL,
    /** 由业务告警派生。 */
    ALARM;

    /** 宽松解析：非法值抛 {@link BizException}（400，文案走 i18n key —— 中文字面量会原样送到英文 / 阿语界面）。 */
    public static CsTicketChannel of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "channel");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.common.invalid_value", v + " (APP/MP/H5/PHONE/EMAIL/MANUAL/ALARM)");
        }
    }
}
