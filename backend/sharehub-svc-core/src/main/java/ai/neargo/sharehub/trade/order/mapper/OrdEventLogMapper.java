package ai.neargo.sharehub.trade.order.mapper;

import ai.neargo.sharehub.trade.order.entity.OrdEventLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 订单状态流水 Mapper（append 表：只 insert + 按 order_no 查，不做 update/delete）。 */
public interface OrdEventLogMapper extends BaseMapper<OrdEventLog> {
}
