package ai.neargo.sharehub.user.core.service.impl;

import ai.neargo.common.core.ServerException;
import ai.neargo.common.core.ErrorCode;
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
            throw ServerException.of(ErrorCode.CONFLICT, "已有进行中的注销申请，冷静期至 " + last.getCoolingUntil());
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
        // 抛 IllegalArgumentException 而不是 IllegalStateException：**这是调用方的问题，不是服务端故障**。
        // 本仓的 GlobalExceptionHandler 只把前者映射成 400，后者落到兜底的 500 ——
        // 于是「没申请过却点撤销」会给用户一个「服务器错误」，还会在 error 日志里留一条带堆栈的 ERROR，
        // 而日志规范要求每条 ERROR 都能回答「谁该做什么」。状态机（Wo/Ord/Settlement）一律用前者，
        // 正是为了实现状态总表里那句「非法迁移一律 400」。
        if (e == null || !PENDING.equals(e.getStatus())) {
            throw new IllegalArgumentException("没有进行中的注销申请，无法撤销");
        }
        // 冷静期过了就不许撤销。接口 javadoc 一直这么写着，而实现没有判 ——
        // 清除作业还没接，状态会一直停在 PENDING，于是「过期不可撤销」这条保护形同虚设。
        // 等作业接上之后，到期那一刻数据可能已经在删，那时候回一句「撤销成功」是假话。
        if (expired(e)) {
            throw new IllegalArgumentException("冷静期已过（" + e.getCoolingUntil() + "），不可撤销");
        }
        e.setStatus(CANCELLED);
        mapper.updateById(e);
        return toVO(e);
    }

    /** 冷静期是否已过。{@code coolingUntil} 存的是 {@code yyyy-MM-dd HH:mm:ss} 文本（与 requestedAt 同源）。 */
    @Override
    public ai.neargo.common.core.PageResult<LogoffItem> pageForOps(Integer page, Integer size, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int sz = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<UsrLogoff> w = new LambdaQueryWrapper<UsrLogoff>()
                .eq(status != null && !status.isBlank(), UsrLogoff::getStatus, status)
                // 冷静期快到的排前面：这个队列存在的意义就是「还来得及处理的那些」
                .orderByAsc(UsrLogoff::getCoolingUntil);
        var r = mapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, sz), w);
        return new ai.neargo.common.core.PageResult<>(
                r.getRecords().stream().map(UserLogoffServiceImpl::toVO).toList(), r.getTotal());
    }

    private static boolean expired(UsrLogoff e) {
        String until = e.getCoolingUntil();
        if (until == null || until.isBlank()) return false;   // 没有截止时间 → 不拿它拒人
        try {
            return LocalDateTime.parse(until, TS).isBefore(LocalDateTime.now());
        } catch (java.time.format.DateTimeParseException ex) {
            // 解析不了就不拦：宁可多让一次撤销，也不要因为格式问题把用户锁在注销里
            return false;
        }
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
