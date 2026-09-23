package ai.neargo.sharehub.common.event.dedup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 去重表的 Mapper。只 insert，不 update、不 delete（清理由任务按时间批量做）。 */
@Mapper
public interface SysEventConsumedMapper extends BaseMapper<SysEventConsumed> {
}
