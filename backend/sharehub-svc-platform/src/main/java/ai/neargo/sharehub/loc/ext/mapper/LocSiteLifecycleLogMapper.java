package ai.neargo.sharehub.loc.ext.mapper;

import ai.neargo.sharehub.loc.ext.entity.LocSiteLifecycleLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 门店生命周期流转留痕（append，只 insert） Mapper（随 {@code @MapperScan} 的 markerInterface 扫描）。 */
public interface LocSiteLifecycleLogMapper extends BaseMapper<LocSiteLifecycleLog> {
}
