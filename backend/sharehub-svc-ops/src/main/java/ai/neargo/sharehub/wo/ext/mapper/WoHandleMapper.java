package ai.neargo.sharehub.wo.ext.mapper;

import ai.neargo.sharehub.wo.ext.entity.WoHandle;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 现场处理留痕（append，只 insert） Mapper（随 {@code @MapperScan} 的 markerInterface 扫描）。 */
public interface WoHandleMapper extends BaseMapper<WoHandle> {
}
