package ai.neargo.powerbank.loc.mapper;

import ai.neargo.powerbank.loc.entity.LocContract;
import ai.neargo.powerbank.loc.entity.LocLocation;
import ai.neargo.powerbank.loc.entity.LocSite;
import ai.neargo.powerbank.loc.entity.LocVenue;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** loc 域四个 Mapper（MyBatis-Plus BaseMapper，自带 CRUD/分页）。集中一文件减少散落。 */
public final class LocMappers {
    private LocMappers() {
    }

    public interface SiteMapper extends BaseMapper<LocSite> {
    }

    public interface LocationMapper extends BaseMapper<LocLocation> {
    }

    public interface VenueMapper extends BaseMapper<LocVenue> {
    }

    public interface ContractMapper extends BaseMapper<LocContract> {
    }
}
