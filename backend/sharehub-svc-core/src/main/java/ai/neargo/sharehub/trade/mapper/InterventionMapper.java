package ai.neargo.sharehub.trade.mapper;

import ai.neargo.sharehub.trade.entity.OrdIntervention;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 订单干预留痕 Mapper。**只 insert 与 select**，不要用它的 update/delete。 */
public interface InterventionMapper extends BaseMapper<OrdIntervention> {
}
