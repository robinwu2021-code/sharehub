package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.port.FileAccessChecker;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocSiteSurvey;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/** 勘测照片的下载鉴权：能按数据范围读到勘测所在的站点，就能看照片。 */
@Component
public class SiteSurveyFileAccessChecker implements FileAccessChecker {

    private final LocMappers.SiteSurveyMapper surveys;
    private final LocMappers.SiteMapper sites;

    public SiteSurveyFileAccessChecker(LocMappers.SiteSurveyMapper surveys, LocMappers.SiteMapper sites) {
        this.surveys = surveys;
        this.sites = sites;
    }

    @Override
    public String bizType() {
        return "SITE_SURVEY";
    }

    @Override
    public boolean canRead(String bizNo) {
        LocSiteSurvey v = surveys.selectOne(new LambdaQueryWrapper<LocSiteSurvey>().eq(LocSiteSurvey::getSurveyNo, bizNo).last("limit 1"));
        // 站点查询带数据范围：范围外即查不到 ⇒ 无权（与「不存在」同一结果）
        return v != null && sites.selectCount(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, v.getSiteNo())) > 0;
    }
}
