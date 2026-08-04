package ai.neargo.sharehub.agent.ext.mapper;

import ai.neargo.sharehub.agent.ext.entity.AgtAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 代理设备/点位划拨记录（append，只 insert） Mapper（随 {@code @MapperScan} 的 markerInterface 扫描）。 */
public interface AgtAssignmentMapper extends BaseMapper<AgtAssignment> {
}
