package ai.neargo.sharehub.wo.ext.mapper;

import ai.neargo.sharehub.wo.ext.entity.WoDispatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 派单/接单记录（append，只 insert） Mapper（随 {@code @MapperScan} 的 markerInterface 扫描）。 */
public interface WoDispatchMapper extends BaseMapper<WoDispatch> {
}
