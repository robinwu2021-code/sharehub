package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FavoriteItem;

/** 收藏门店（usr_favorite，c-app {@code /mp/user/favorites}）。按属主过滤。 */
public interface UserFavoriteService {

    PageResult<FavoriteItem> pageByOwner(String cUserNo, Integer page, Integer size);

    /**
     * 这家店在不在我的收藏里。
     *
     * <p><b>为什么不用 {@code pageByOwner(me, 1, 200)} 再 contains</b>：那把分页默认值
     * 当成了业务上限 —— 收藏超过 200 家之后，靠后那些店的心形**永远不亮**，
     * 而页面照常渲染、接口照常 200。
     */
    boolean isFavorite(String cUserNo, String siteNo);

    /**
     * 我收藏的全部站点号（不分页）。
     *
     * <p>不分页是对的：这是**用户自己的行**，行数由他自己的行为决定，
     * 而收藏夹本来就要全量展示。用 {@code pageByOwner(1, 200)} 代替它，
     * 第 201 家起就悄悄从收藏夹里消失了。
     */
    java.util.Set<String> siteNosOf(String cUserNo);

    /**
     * 收藏 / 取消收藏（切换）。UK(c_user_no, site_no) 保证同一门店只有一行；
     * 取消走软删（{@code deleted=1}），再次收藏时复活原行而不是插重复行。
     *
     * @return 切换后是否处于「已收藏」
     */
    boolean toggle(String cUserNo, String siteNo);
}
