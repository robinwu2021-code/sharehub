package ai.neargo.sharehub.user.core.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FavoriteItem;

/** 收藏门店（usr_favorite，c-app {@code /mp/user/favorites}）。按属主过滤。 */
public interface UserFavoriteService {

    PageResult<FavoriteItem> pageByOwner(String cUserNo, Integer page, Integer size);

    /**
     * 收藏 / 取消收藏（切换）。UK(c_user_no, site_no) 保证同一门店只有一行；
     * 取消走软删（{@code deleted=1}），再次收藏时复活原行而不是插重复行。
     *
     * @return 切换后是否处于「已收藏」
     */
    boolean toggle(String cUserNo, String siteNo);
}
