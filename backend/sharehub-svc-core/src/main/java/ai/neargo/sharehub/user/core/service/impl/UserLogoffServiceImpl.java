package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.sharehub.user.core.dto.UserCoreDtos.LogoffItem;
import ai.neargo.sharehub.user.core.entity.UsrLogoff;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrLogoffMapper;
import ai.neargo.sharehub.user.core.service.UserLogoffService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 注销申请实现。 */
@Service
public class UserLogoffServiceImpl implements UserLogoffService {

    /** 冷静期默认 15 天 —— 待法务确认后改为读 {@code sys_param}（[db-design §十二 待确认 5]）。 */
    private static final int COOLING_DAYS = 15;
    private static final String TENANT_MAIN = "MAIN";
    private static final String PENDING = "PENDING";
    private static final String CANCELLED = "CANCELLED";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UsrLogoffMapper mapper;

    public UserLogoffServiceImpl(UsrLogoffMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LogoffItem current(String cUserNo) {
        UsrLogoff e = latest(cUserNo);
        return e == null ? null : toVO(e);
    }

    @Override
    public LogoffItem apply(String cUserNo) {
        UsrLogoff last = latest(cUserNo);
        if (last != null && PENDING.equals(last.getStatus())) {
            throw new IllegalStateException("已有进行中的注销申请，冷静期至 " + last.getCoolingUntil());
        }
        LocalDateTime now = LocalDateTime.now();
        UsrLogoff e = new UsrLogoff();
        e.setTenantId(TENANT_MAIN);
        e.setCUserNo(cUserNo);
        e.setRequestedAt(now.format(TS));
        e.setCoolingUntil(now.plusDays(COOLING_DAYS).format(TS));
        e.setStatus(PENDING);
        mapper.insert(e);
        // TODO(清除作业)：到期扫描 status=PENDING AND cooling_until <= now → 执行清除/匿名化 → DONE + purged_at。
        //  清除范围（哪些表物理删、哪些匿名化保留财务留痕）待法务确认后写入 PDPL 专篇。
        return toVO(e);
    }

    @Override
    public LogoffItem cancel(String cUserNo) {
        UsrLogoff e = latest(cUserNo);
        if (e == null || !PENDING.equals(e.getStatus())) {
            throw new IllegalStateException("没有进行中的注销申请: " + cUserNo);
        }
        e.setStatus(CANCELLED);
        mapper.updateById(e);
        return toVO(e);
    }

    private UsrLogoff latest(String cUserNo) {
        return mapper.selectOne(new LambdaQueryWrapper<UsrLogoff>()
                .eq(UsrLogoff::getCUserNo, cUserNo)
                .orderByDesc(UsrLogoff::getId)
                .last("limit 1"));
    }

    private static LogoffItem toVO(UsrLogoff e) {
        return new LogoffItem(e.getCUserNo(), e.getRequestedAt(), e.getCoolingUntil(),
                e.getStatus(), e.getPurgedAt());
    }
}
