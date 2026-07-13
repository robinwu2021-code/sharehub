package ai.neargo.powerbank.wo.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto.OkResult;
import ai.neargo.powerbank.dto.Dto.WorkOrder;
import ai.neargo.powerbank.wo.WoStateMachine;
import ai.neargo.powerbank.wo.entity.WoOrder;
import ai.neargo.powerbank.wo.mapper.WoMapper;
import ai.neargo.powerbank.wo.service.WorkOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/** 工单业务实现。派单走独立状态机 {@link WoStateMachine}；权限在 Controller，数据过滤横切。 */
@Service
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WoMapper mapper;
    private final WoStateMachine stateMachine;

    public WorkOrderServiceImpl(WoMapper mapper, WoStateMachine stateMachine) {
        this.mapper = mapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<WorkOrder> page(Integer page, Integer size, String keyword, String status, String type) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<WoOrder> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(WoOrder::getWoNo, keyword).or().like(WoOrder::getCabinetNo, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(WoOrder::getStatus, status);
        if (type != null && !type.isBlank()) w.eq(WoOrder::getType, type);
        w.orderByAsc(WoOrder::getId);
        Page<WoOrder> r = mapper.selectPage(new Page<>(p, s), w);
        List<WorkOrder> rows = r.getRecords().stream().map(WorkOrderServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public OkResult dispatch(String woNo, String assignee) {
        WoOrder e = mapper.selectOne(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getWoNo, woNo));
        if (e == null) throw new IllegalArgumentException("工单不存在: " + woNo);
        e.setStatus(stateMachine.next(e.getStatus(), "DISPATCH")); // 非法迁移由状态机拒
        e.setAssigneeName(assignee);
        mapper.updateById(e);
        return new OkResult(true);
    }

    private static WorkOrder toVO(WoOrder e) {
        return new WorkOrder(e.getWoNo(), e.getType(), e.getSource(), e.getPriority(), e.getCabinetNo(),
                e.getLocationName(), e.getStatus(), e.getAssigneeName(), e.getSlaDueAt(),
                e.getDescription(), e.getWoCreatedAt());
    }
}
