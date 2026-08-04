package ai.neargo.sharehub.trade.pay.service.impl;

import ai.neargo.sharehub.trade.pay.entity.PayEventLog;
import ai.neargo.sharehub.trade.pay.mapper.PayEventLogMapper;
import ai.neargo.sharehub.trade.pay.service.PayEventLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回调幂等实现。
 *
 * <p>幂等的真正保证是**表上的 UK(ref_no, event_type)**，不是这里的先查后插 ——
 * 两个回调并发进来时 {@link #received} 都会返回 false，最终由库拒掉第二条插入
 * （捕获 {@link DuplicateKeyException} 转成「这是重放」）。
 */
@Service
public class PayEventLogServiceImpl implements PayEventLogService {

    private final PayEventLogMapper mapper;

    public PayEventLogServiceImpl(PayEventLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean alreadyProcessed(String refNo, String eventType) {
        PayEventLog e = selectOne(refNo, eventType);
        return e != null && e.getProcessed() != null && e.getProcessed() == 1;
    }

    @Override
    public boolean received(String refNo, String eventType) {
        return selectOne(refNo, eventType) != null;
    }

    @Override
    public boolean record(String refNo, String eventType, String raw) {
        if (refNo == null || refNo.isBlank()) throw new IllegalArgumentException("refNo 不能为空");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType 不能为空");

        PayEventLog e = new PayEventLog();
        e.setSource("NEARPAY");
        e.setRefNo(refNo);
        e.setEventType(eventType);
        e.setRaw(raw);
        e.setProcessed(0);
        try {
            mapper.insert(e);
            return true;
        } catch (DuplicateKeyException dup) {
            return false; // UK 撞了 = 同一事件重放，交给调用方按幂等放行
        }
    }

    @Override
    public boolean markProcessed(String refNo, String eventType) {
        PayEventLog e = selectOne(refNo, eventType);
        if (e == null) return false;
        e.setProcessed(1);
        return mapper.updateById(e) > 0;
    }

    @Override
    public List<PayEventLog> byRef(String refNo) {
        if (refNo == null || refNo.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<PayEventLog>()
                .eq(PayEventLog::getRefNo, refNo)
                .orderByAsc(PayEventLog::getId));
    }

    private PayEventLog selectOne(String refNo, String eventType) {
        if (refNo == null || refNo.isBlank() || eventType == null || eventType.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<PayEventLog>()
                .eq(PayEventLog::getRefNo, refNo)
                .eq(PayEventLog::getEventType, eventType)
                .last("limit 1"));
    }
}
