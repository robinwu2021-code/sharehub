package ai.neargo.sharehub.gw.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.gw.dto.GwDtos.CommandRecord;
import ai.neargo.sharehub.gw.entity.GwCommandLog;
import ai.neargo.sharehub.gw.mapper.GwCommandLogMapper;
import ai.neargo.sharehub.gw.service.CommandLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/** 指令记录查询实现。只读，无写路径 —— 写在网关南向链路。 */
@Service
public class CommandLogServiceImpl implements CommandLogService {

    private final GwCommandLogMapper mapper;

    public CommandLogServiceImpl(GwCommandLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<CommandRecord> page(Integer page, Integer size, String keyword,
                                          String cabinetNo, String type, String status) {
        LambdaQueryWrapper<GwCommandLog> w = new LambdaQueryWrapper<>();
        if (notBlank(keyword)) {
            // commandId / sn / cabinetNo / orderNo：四种线索都可能是排障入口
            w.and(q -> q.like(GwCommandLog::getCommandId, keyword)
                    .or().like(GwCommandLog::getSn, keyword)
                    .or().like(GwCommandLog::getCabinetNo, keyword)
                    .or().like(GwCommandLog::getOrderNo, keyword));
        }
        if (notBlank(cabinetNo)) w.eq(GwCommandLog::getCabinetNo, cabinetNo);
        if (notBlank(type)) w.eq(GwCommandLog::getType, type);
        if (notBlank(status)) w.eq(GwCommandLog::getStatus, status);
        w.orderByDesc(GwCommandLog::getId); // append 表自增即时间序，比排 sent_at 稳（后者可空）

        Page<GwCommandLog> r = mapper.selectPage(new Page<>(norm(page), normSize(size)), w);
        List<CommandRecord> rows = r.getRecords().stream()
                .map(CommandLogServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public CommandRecord getByCommandId(String commandId) {
        if (!notBlank(commandId)) return null;
        GwCommandLog e = mapper.selectOne(new LambdaQueryWrapper<GwCommandLog>()
                .eq(GwCommandLog::getCommandId, commandId).last("limit 1"));
        return e == null ? null : toVO(e);
    }

    private static CommandRecord toVO(GwCommandLog e) {
        return new CommandRecord(e.getCommandId(), e.getSn(), e.getCabinetNo(), e.getType(),
                e.getSlotIndex(), e.getStatus(), e.getRetry(), e.getOrderNo(), e.getOperator(),
                e.getSentAt(), e.getConfirmedAt(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int norm(Integer v) {
        return (v == null || v < 1) ? 1 : v;
    }

    private static int normSize(Integer v) {
        return (v == null || v < 1) ? 10 : Math.min(v, 200);
    }
}
