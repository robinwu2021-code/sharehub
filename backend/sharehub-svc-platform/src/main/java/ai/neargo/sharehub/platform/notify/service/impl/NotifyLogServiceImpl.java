package ai.neargo.sharehub.platform.notify.service.impl;

import ai.neargo.sharehub.platform.notify.NotifyLogStatus;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.platform.notify.NotifyTargets;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogStats;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyLog;
import ai.neargo.sharehub.platform.notify.mapper.NotifyLogMapper;
import ai.neargo.sharehub.platform.notify.service.NotifyLogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 发送记录实现（append 表：只有查询与追加）。
 *
 * <p><b>受控排序</b>：{@link #SORTABLE} 是 {@code 前端字段名 → 数据库列名} 的白名单，
 * 与 {@code ops-web/lib/api/query.ts} 对齐。传入未登记的字段直接抛异常，而不是"忽略后按默认排"
 * —— 静默忽略会让前端以为排序生效了；更重要的是排序列会被拼进 SQL，白名单是这里唯一的注入防线。
 */
@Service
public class NotifyLogServiceImpl implements NotifyLogService {

    private static final String TENANT_MAIN = "MAIN";
    /** 受控排序白名单（[api/README §1.4]：notify-logs 只允许 sentAt|cost）。 */
    private static final Map<String, String> SORTABLE = Map.of("sentAt", "sent_at", "cost", "cost");

    private final NotifyLogMapper mapper;

    public NotifyLogServiceImpl(NotifyLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<NotifyLogVO> page(Integer page, Integer size, String keyword,
                                        String channel, String status, String scene, String sort, String dir) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        QueryWrapper<NotifyLog> w = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like("log_no", kw).or().like("target", kw).or().like("template_no", kw));
        }
        if (channel != null && !channel.isBlank()) w.eq("channel", channel);
        if (status != null && !status.isBlank()) w.eq("status", status);
        if (scene != null && !scene.isBlank()) w.eq("scene", scene);

        boolean asc = "asc".equalsIgnoreCase(dir);
        if (sort == null || sort.isBlank()) {
            w.orderByDesc("id"); // 默认最新在前；id 单调，同毫秒也不乱序
        } else {
            String column = SORTABLE.get(sort);
            if (column == null) {
                throw new IllegalArgumentException("不支持的排序字段: " + sort + "（仅允许 sentAt|cost）");
            }
            w.orderBy(true, asc, column);
        }

        Page<NotifyLog> r = mapper.selectPage(new Page<>(p, s), w);
        List<NotifyLogVO> rows = r.getRecords().stream().map(NotifyLogServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public NotifyLogStats stats() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();

        long sent = mapper.selectCount(new QueryWrapper<NotifyLog>()
                .ge("created_at", dayStart).eq("status", NotifyLogStatus.SENT.name()));
        long failed = mapper.selectCount(new QueryWrapper<NotifyLog>()
                .ge("created_at", dayStart).eq("status", NotifyLogStatus.FAILED.name()));

        long total = sent + failed;
        BigDecimal failRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(failed).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

        // 成本合计走全量扫当日行：口径必须是全量，不能是当前分页的合计（[api/README §7.3]）
        List<NotifyLog> today = mapper.selectList(new QueryWrapper<NotifyLog>().ge("created_at", dayStart));
        BigDecimal cost = today.stream()
                .map(e -> e.getCost() == null ? BigDecimal.ZERO : e.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = today.stream()
                .map(NotifyLog::getCurrency).filter(c -> c != null && !c.isBlank())
                .findFirst().orElse("AED");

        return new NotifyLogStats(sent, failed, failRate, cost, currency);
    }

    @Override
    public NotifyLogVO append(NotifyLog e) {
        if (e.getTenantId() == null) e.setTenantId(TENANT_MAIN);
        if (e.getLogNo() == null || e.getLogNo().isBlank()) e.setLogNo(nextLogNo());
        e.setTarget(NotifyTargets.maskTarget(e.getTarget())); // 存储即脱敏，明文不入库
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(NotifyLogStatus.SENT.name());
        if (e.getCost() == null) e.setCost(BigDecimal.ZERO);
        if (e.getCurrency() == null || e.getCurrency().isBlank()) e.setCurrency("AED");
        if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
        return toVO(e);
    }

    /** 取号：扫同前缀最大号 +1（[db-design §1.4.1]，**禁止**「前缀 + 行数」）。并发撞号由 UK 兜底。 */
    private String nextLogNo() {
        NotifyLog top = mapper.selectOne(new QueryWrapper<NotifyLog>()
                .likeRight("log_no", BizKey.NOTIFY_LOG)
                .orderByDesc("log_no")
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getLogNo() != null && top.getLogNo().length() > BizKey.NOTIFY_LOG.length()) {
            String digits = top.getLogNo().substring(BizKey.NOTIFY_LOG.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号，UK 兜底
                }
            }
        }
        return BizKey.NOTIFY_LOG + String.format("%06d", n + 1);
    }

    private static NotifyLogVO toVO(NotifyLog e) {
        return new NotifyLogVO(e.getLogNo(), e.getChannel(), e.getTemplateNo(), e.getTarget(),
                e.getScene(), e.getSentAt(), e.getStatus(), e.getFailReason(), e.getCost(), e.getCurrency(),
                e.getIdempotencyKey(), e.getResendOf());
    }

    @Override
    @Transactional
    public NotifyLogVO resend(String logNo, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 不给幂等键就拒绝，而不是"帮它生成一个" —— 生成的话双击提交会产生两个不同的键，
            // 幂等就形同虚设，用户实实在在收到两条短信。
            throw new IllegalArgumentException("重发必须携带 idempotencyKey");
        }
        NotifyLog src = mapper.selectOne(new QueryWrapper<NotifyLog>()
                .eq("log_no", logNo).last("limit 1"));
        if (src == null) {
            throw new IllegalArgumentException("发送记录不存在: " + logNo);
        }

        NotifyLog e = new NotifyLog();
        e.setLogNo(nextLogNo());
        e.setTenantId(src.getTenantId() == null ? TENANT_MAIN : src.getTenantId());
        // 沿用原记录的投递要素 —— 重发就是"同样的东西再发一次"
        e.setChannel(src.getChannel());
        e.setTemplateNo(src.getTemplateNo());
        e.setTarget(src.getTarget());
        e.setScene(src.getScene());
        e.setCost(src.getCost());
        e.setCurrency(src.getCurrency());
        e.setSentAt(LocalDateTime.now().toString().replace('T', ' '));
        e.setCreatedAt(LocalDateTime.now());
        e.setStatus(NotifyLogStatus.SENT.name());
        e.setIdempotencyKey(idempotencyKey);
        e.setResendOf(logNo);
        try {
            mapper.insert(e);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 唯一索引是**执行手段**：并发下应用层先查后插会漏，只有约束拦得住。
            throw new IllegalStateException("该操作已提交过（idempotencyKey 重复），未重复发送");
        }
        return toVO(mapper.selectOne(new QueryWrapper<NotifyLog>()
                .eq("log_no", e.getLogNo()).last("limit 1")));
    }
}
