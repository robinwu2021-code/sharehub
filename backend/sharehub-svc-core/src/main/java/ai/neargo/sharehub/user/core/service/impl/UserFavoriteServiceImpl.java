package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.user.core.dto.UserCoreDtos.FavoriteItem;
import ai.neargo.sharehub.user.core.entity.UsrFavorite;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrFavoriteMapper;
import ai.neargo.sharehub.user.core.service.UserFavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 收藏门店实现。门店名按批回填（列表要显示店名，收藏表只存 {@code site_no}）。 */
@Service
public class UserFavoriteServiceImpl implements UserFavoriteService {

    private static final String TENANT_MAIN = "MAIN";

    private final UsrFavoriteMapper mapper;
    private final SiteQueryPort sites;

    public UserFavoriteServiceImpl(UsrFavoriteMapper mapper, SiteQueryPort sites) {
        this.mapper = mapper;
        this.sites = sites;
    }

    @Override
    public PageResult<FavoriteItem> pageByOwner(String cUserNo, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        Page<UsrFavorite> r = mapper.selectPage(new Page<>(p, s),
                new LambdaQueryWrapper<UsrFavorite>()
                        .eq(UsrFavorite::getCUserNo, cUserNo)
                        .orderByDesc(UsrFavorite::getId));

        Map<String, String> names = siteNames(r.getRecords().stream().map(UsrFavorite::getSiteNo).toList());
        List<FavoriteItem> rows = r.getRecords().stream()
                .map(e -> new FavoriteItem(e.getSiteNo(), names.get(e.getSiteNo()),
                        e.getCreatedAt() == null ? null : e.getCreatedAt().toString()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public boolean toggle(String cUserNo, String siteNo) {
        UsrFavorite active = mapper.selectOne(new LambdaQueryWrapper<UsrFavorite>()
                .eq(UsrFavorite::getCUserNo, cUserNo)
                .eq(UsrFavorite::getSiteNo, siteNo)
                .last("limit 1"));

        if (active != null) {
            mapper.deleteById(active.getId()); // @TableLogic → 软删，保留「曾收藏过」的行为数据
            return false;
        }
        if (mapper.revive(cUserNo, siteNo) > 0) {
            return true; // 之前取消过，复活原行；直接 INSERT 会撞 UK(c_user_no, site_no)
        }
        UsrFavorite e = new UsrFavorite();
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setSiteNo(siteNo);
        mapper.insert(e);
        return true;
    }

    private Map<String, String> siteNames(List<String> siteNos) {
        List<String> keys = siteNos.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
        if (keys.isEmpty()) return Map.of();
        // 经 platform 的 Port 取，不直连它的表（ADR-017 纪律三）。
        // 批量一次取回，避免 N+1 —— Port 只提供批量接口正是为了防住这一点。
        return sites.briefsByNos(keys).stream()
                .filter(x -> x.name() != null)
                .collect(Collectors.toMap(SiteBrief::siteNo, SiteBrief::name, (a, b) -> a));
    }
}
