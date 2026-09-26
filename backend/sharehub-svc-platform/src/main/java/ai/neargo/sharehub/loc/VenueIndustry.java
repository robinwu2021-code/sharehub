package ai.neargo.sharehub.loc;

import java.util.Arrays;
import java.util.Optional;

/**
 * 场地方行业（{@code loc_venue.industry} / {@code loc_venue_onboarding.industry}，
 * 词表见 V121 的列注释）。
 *
 * <h3>为什么要收成词表</h3>
 * 这一列原来是自由文本框。生产 5 行里有三行是错的 ——
 * Emaar Malls（商业地产集团）填成「零售」、Majid Al Futtaim 填成「航空」、
 * DXB Airports 填成「地产」。自由文本的问题不是填错，是<b>填错了没人拦也没人看得出来</b>，
 * 而这一列将来要用来按行业看坪效、按行业定价、按行业分派 BD。
 *
 * <h3>与站点「场景类型」是两个轴，不要合并</h3>
 * 场地方的行业说的是<b>这家公司做什么</b>（Emaar 是商业地产），
 * 站点的场景说的是<b>这个点位在什么地方</b>（Emaar 名下那个点位在商场里）。
 * 两者取值高度重叠，但一个地产集团运营商场时二者恰恰不同 —— 合并会表达不了这种情况。
 *
 * <h3>与运营端 {@code lib/types/location.ts} 的 VenueIndustry 同名同值</h3>
 * 这一对因此进入两端同名词表比对（{@code StatusVocabularyAcrossEndsTest}）：
 * 哪天一边加了一档而另一边没加，卡口会红 —— 否则界面上选得出来、后端存不进去。
 */
public enum VenueIndustry {

    /** 购物中心 —— 本业务最主要的场地类型。 */
    SHOPPING_MALL,
    /** 商业地产（集团型业主，名下常有多个业态）。 */
    COMMERCIAL_RE,
    /** 机场与航空。 */
    AIRPORT,
    /** 交通枢纽：地铁 / 高铁 / 客运站 / 港口。 */
    TRANSPORT_HUB,
    /** 餐饮。 */
    F_AND_B,
    /** 酒店与住宿。 */
    HOTEL,
    /** 医疗：医院 / 诊所 / 体检中心。 */
    MEDICAL,
    /** 教育：学校 / 培训机构。 */
    EDUCATION,
    /** 办公：写字楼 / 产业园。 */
    OFFICE,
    /** 娱乐：影院 / KTV / 健身 / 电竞。 */
    ENTERTAINMENT,
    /** 零售（单体门店、连锁便利等，区别于购物中心这种集合业态）。 */
    RETAIL,
    /** 景区与文旅。 */
    SCENIC,
    /** 其它。**保留一档兜底**：没有兜底时运营会被逼着乱选一个，那比「其它」更难查。 */
    OTHER;

    public static Optional<VenueIndustry> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
