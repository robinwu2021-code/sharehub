package ai.neargo.sharehub.alarm.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.AlarmCloseReason;
import ai.neargo.sharehub.alarm.AlarmNoticeStatus;
import ai.neargo.sharehub.alarm.AlarmStateMachine;
import ai.neargo.sharehub.alarm.AlarmStatus;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AckResult;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmNotice;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRecord;
import ai.neargo.sharehub.alarm.dto.AlarmDtos.WorkOrderRef;
import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.alarm.entity.DevAlarmNotice;
import ai.neargo.sharehub.alarm.mapper.DevAlarmMapper;
import ai.neargo.sharehub.alarm.mapper.DevAlarmNoticeMapper;
import ai.neargo.sharehub.alarm.service.AlarmService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 告警记录业务实现。手写 {@code LambdaQueryWrapper}（保留编译期列名安全），
 * 风格照 {@code wo.service.impl.WorkOrderServiceImpl}。
 */
@Service
public class AlarmServiceImpl implements AlarmService {

    private final DevAlarmMapper mapper;
    private final DevAlarmNoticeMapper noticeMapper;
    private final AlarmStateMachine stateMachine;
    // —— 2026-09-25 业务告警 ——
    private final ai.neargo.sharehub.alarm.engine.AlarmEngine engine;
    private final ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper codeMapper;
    private final ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.AlarmLogMapper logMapper;
    private final ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.RouteMapper routeMapper;
    private final ai.neargo.sharehub.wo.ext.service.WoOpsService workOrders;

    public AlarmServiceImpl(DevAlarmMapper mapper, DevAlarmNoticeMapper noticeMapper,
                            AlarmStateMachine stateMachine,
                            ai.neargo.sharehub.alarm.engine.AlarmEngine engine,
                            ai.neargo.sharehub.alarm.mapper.DevAlarmCodeMapper codeMapper,
                            ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.AlarmLogMapper logMapper,
                            ai.neargo.sharehub.alarm.mapper.AlarmEngineMappers.RouteMapper routeMapper,
                            ai.neargo.sharehub.wo.ext.service.WoOpsService workOrders) {
        this.workOrders = workOrders;
        this.engine = engine;
        this.codeMapper = codeMapper;
        this.logMapper = logMapper;
        this.routeMapper = routeMapper;
        this.mapper = mapper;
        this.noticeMapper = noticeMapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<AlarmRecord> page(Integer page, Integer size, String keyword,
                                        String level, String status, String cabinetNo) {
        LambdaQueryWrapper<DevAlarm> w = new LambdaQueryWrapper<>();
        if (notBlank(keyword)) {
            // 三个都能搜：告警号（精确追踪）、机柜号（现场排障）、厂商原始码（对厂商追问时手里只有它）
            w.and(q -> q.like(DevAlarm::getAlarmNo, keyword)
                    .or().like(DevAlarm::getCabinetNo, keyword)
                    .or().like(DevAlarm::getAlarmCode, keyword)
                    .or().like(DevAlarm::getVendorErrorCode, keyword));
        }
        if (notBlank(level)) w.eq(DevAlarm::getLevel, level);
        if (notBlank(status)) w.eq(DevAlarm::getStatus, status);
        if (notBlank(cabinetNo)) w.eq(DevAlarm::getCabinetNo, cabinetNo);
        // 按发生时刻倒序：告警列表的唯一有用默认序，且与索引 (tenant_id,agent_no,status,occurred_at) 同向
        w.orderByDesc(DevAlarm::getOccurredAt).orderByDesc(DevAlarm::getId);

        Page<DevAlarm> r = mapper.selectPage(new Page<>(norm(page, 1), normSize(size)), w);
        List<AlarmRecord> rows = r.getRecords().stream().map(AlarmServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public PageResult<AlarmNotice> pageNotices(Integer page, Integer size, String keyword,
                                               String alarmNo, String channel, String status) {
        LambdaQueryWrapper<DevAlarmNotice> w = new LambdaQueryWrapper<>();
        if (notBlank(keyword)) {
            w.and(q -> q.like(DevAlarmNotice::getNoticeNo, keyword)
                    .or().like(DevAlarmNotice::getAlarmNo, keyword)
                    .or().like(DevAlarmNotice::getTarget, keyword));
        }
        if (notBlank(alarmNo)) w.eq(DevAlarmNotice::getAlarmNo, alarmNo);
        if (notBlank(channel)) w.eq(DevAlarmNotice::getChannel, channel);
        if (notBlank(status)) w.eq(DevAlarmNotice::getStatus, status);
        w.orderByDesc(DevAlarmNotice::getId);

        Page<DevAlarmNotice> r = noticeMapper.selectPage(new Page<>(norm(page, 1), normSize(size)), w);
        List<AlarmNotice> rows = r.getRecords().stream().map(AlarmServiceImpl::toNoticeVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public AckResult ack(String alarmNo, String remark) {
        DevAlarm e = selectByNo(alarmNo);
        e.setStatus(stateMachine.next(e.getStatus(), "ACK")); // 非法迁移由状态机拒
        if (notBlank(remark)) e.setRemark(remark);
        mapper.updateById(e);
        return new AckResult(e.getAlarmNo(), e.getStatus());
    }

    @Override
    public AckResult close(String alarmNo, String reason, String note) {
        StaffContext.require();                                   // 关闭人不接受客户端传：必须是登录员工
        // 2026-09-25：所有关闭都经引擎 —— 释放本告警持有的停借 / 锁仓、联动撤单或并单、写时间线
        engine.closeManually(alarmNo, reason, note);
        DevAlarm e = selectByNo(alarmNo);
        return new AckResult(e.getAlarmNo(), e.getStatus());
    }

    @Override
    public WorkOrderRef toWorkOrder(String alarmNo) {
        DevAlarm e = selectByNo(alarmNo);

        // —— 幂等：alarmNo 是幂等键 ——
        // wo_no 非空即表示此告警已开过单，直接返回首次生成的单号，不再建第二张。
        // 这是幂等的**快路径**；最终硬约束在 wo_order.source_ref 的 UNIQUE(alarmNo) 上（db-design §1.6），
        // 并发下两个请求可能同时看到 wo_no 为空，那时由 UNIQUE 兜底、建单方捕获 DuplicateKeyException 后回读。
        if (notBlank(e.getWoNo())) {
            return new WorkOrderRef(e.getAlarmNo(), e.getWoNo(), false);
        }

        // 2026-09-25：经引擎处置（取代原先的抛异常）。存量设备告警按故障工单处置，业务告警按码的路由 ——
        // 路由不是工单（自愈 / 待办 / 客服）时返回的是对应单号；仅通知或自愈成功则没有单可返回
        String ref = engine.disposeNow(alarmNo, java.time.LocalDateTime.now());
        if (ref == null) {
            throw ai.neargo.common.core.ServerException.of(ai.neargo.common.core.ErrorCode.CONFLICT,
                    "该告警的处置方式不产生工单（已自愈或仅通知），请刷新查看");
        }
        return new WorkOrderRef(e.getAlarmNo(), ref, true);
    }

    // ——————————————————————— 内部 ———————————————————————

    private DevAlarm selectByNo(String alarmNo) {
        DevAlarm e = mapper.selectOne(new LambdaQueryWrapper<DevAlarm>()
                .eq(DevAlarm::getAlarmNo, alarmNo).last("limit 1"));
        if (e == null) throw BizException.notFound(alarmNo);
        return e;
    }

    private static AlarmRecord toVO(DevAlarm e) {
        // siteName 需 join loc_site 取名；场地域归其他分片，先出 siteNo，前端可先按号展示。
        return new AlarmRecord(e.getAlarmNo(), e.getCabinetNo(), e.getSiteNo(), null,
                e.getAgentNo(), e.getVendorCode(), e.getAlarmCode(), e.getVendorErrorCode(),
                e.getLevel(), e.getSource(), e.getOccurredAt(), e.getStatus(),
                e.getWoNo(), e.getRemark(), e.getDedupKey(), e.getCount(),
                e.getCloseReason(), e.getCloseNote(), e.getClosedBy(),
                e.getClosedAt() == null ? null : e.getClosedAt().toString(),
                e.getDomain() == null ? null : new ai.neargo.sharehub.alarm.dto.AlarmDtos.BusinessInfo(e.getDomain(),
                        e.getSubjectType(), e.getSubjectNo(), e.getCause(), e.getPriority(), e.getImpactScope(),
                        e.getImpactPeriod(), e.getSiteTier(), e.getInFlightOrders(), e.getDispositionType(),
                        e.getDispositionRef(), e.getFirstOccurredAt(), e.getLastOccurredAt(), e.getDueAt(),
                        e.getRecoveredAt(), e.getParentAlarmNo()));
    }

    private static AlarmNotice toNoticeVO(DevAlarmNotice e) {
        return new AlarmNotice(e.getNoticeNo(), e.getAlarmNo(), e.getChannel(), e.getTarget(),
                e.getSentAt(), e.getStatus(), e.getFailReason(),
                e.getIdempotencyKey(), e.getResendOf());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 页码归一：空/非正 → 1。 */
    private static int norm(Integer v, int fallback) {
        return (v == null || v < 1) ? fallback : v;
    }

    /** 页长归一：空/非正 → 10；上限 200，防单次拉全表。 */
    private static int normSize(Integer v) {
        return (v == null || v < 1) ? 10 : Math.min(v, 200);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int autoRaiseWorkOrders() {
        // 只捞「未处理且尚无工单」的 —— wo_no 非空即已开过单，跳过。
        // **幂等靠这个条件本身**，不靠调用方记得别点两次。
        //
        // 状态值取自 AlarmStateMachine 的常量，不写裸串：这里原本写的是 "ACK"，
        // 而 "ACK" 是**事件名**，落库的状态是 ACKED —— 于是已受理的告警永远开不出工单，
        // 不报错、不留日志。差一个字母，编译器无从分辨。由 AlarmStatusVocabularyTest 守住。
        var w = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DevAlarm>()
                .isNull(DevAlarm::getWoNo)
                .in(DevAlarm::getStatus, List.of(AlarmStatus.OPEN.name(), AlarmStatus.ACKED.name()));
        // 2026-09-25：不再写占位单号（「WO-AUTO-…」指向不存在的工单）。业务告警按到期时刻处置；
        // 存量设备告警（无开单延迟概念）逐条按故障工单处置 —— 都走引擎，幂等靠 disposition_ref
        int n = engine.dispatchDue(java.time.LocalDateTime.now());
        for (DevAlarm a : mapper.selectList(w.ne(DevAlarm::getSource, "EVAL"))) {
            if (engine.disposeNow(a.getAlarmNo(), java.time.LocalDateTime.now()) != null) n++;
        }
        return n;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object resendNotice(String noticeNo, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 通知是真发真扣钱，双击不该发两条。不给键就拒，不"帮它生成"。
            throw new IllegalArgumentException("重发告警通知必须携带 idempotencyKey");
        }
        // 幂等：同键已重发过 → 返回首次结果，不落第二行（V31 idempotency_key）
        DevAlarmNotice dup = noticeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DevAlarmNotice>()
                        .eq(DevAlarmNotice::getIdempotencyKey, idempotencyKey).last("limit 1"));
        if (dup != null) return toNoticeVO(dup);

        DevAlarmNotice src = noticeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DevAlarmNotice>()
                        .eq(DevAlarmNotice::getNoticeNo, noticeNo).last("limit 1"));
        if (src == null) throw BizException.notFound(noticeNo);

        // 重发 = 新行指回源行（resend_of），不改写源行 —— 发送历史是审计事实
        DevAlarmNotice e = new DevAlarmNotice();
        e.setNoticeNo(ai.neargo.common.core.IdGenerator.next(ai.neargo.sharehub.common.BizKey.ALARM_NOTICE));
        e.setTenantId(src.getTenantId());
        e.setAlarmNo(src.getAlarmNo());
        e.setChannel(src.getChannel());
        e.setTarget(src.getTarget());
        e.setSentAt(java.time.LocalDateTime.now().toString());
        // 触达通道未接（notify 域 stub），先落 SENT 占位；接通后回写真实回执（见 AlarmNoticeStatus 类注释）
        e.setStatus(AlarmNoticeStatus.SENT.name());
        e.setIdempotencyKey(idempotencyKey);
        e.setResendOf(noticeNo);
        noticeMapper.insert(e);
        return toNoticeVO(e);
    }

    // ——————————————————————— 业务告警查询（2026-09-25）———————————————————————

    @Override
    public PageResult<AlarmRecord> page(AlarmQuery q) {
        LambdaQueryWrapper<DevAlarm> w = new LambdaQueryWrapper<>();
        if (notBlank(q.keyword())) {
            String k = q.keyword().trim();
            w.and(x -> x.like(DevAlarm::getAlarmNo, k).or().like(DevAlarm::getCabinetNo, k).or().like(DevAlarm::getAlarmCode, k)
                    .or().like(DevAlarm::getVendorErrorCode, k).or().eq(DevAlarm::getSubjectNo, k).or().eq(DevAlarm::getSiteNo, k));
        }
        if (notBlank(q.level())) w.eq(DevAlarm::getLevel, q.level());
        if (notBlank(q.status())) w.in(DevAlarm::getStatus, List.of(q.status().split(",")));
        if (notBlank(q.cabinetNo())) w.eq(DevAlarm::getCabinetNo, q.cabinetNo());
        if (notBlank(q.domain())) w.in(DevAlarm::getDomain, List.of(q.domain().split(",")));
        if (notBlank(q.subjectType())) w.eq(DevAlarm::getSubjectType, q.subjectType());
        if (notBlank(q.siteNo())) w.eq(DevAlarm::getSiteNo, q.siteNo());
        if (notBlank(q.cause())) w.eq(DevAlarm::getCause, q.cause());
        if (notBlank(q.disposition())) w.eq(DevAlarm::getDispositionType, q.disposition());
        if (q.from() != null) w.ge(DevAlarm::getOccurredAt, q.from().toString());
        if (q.to() != null) w.lt(DevAlarm::getOccurredAt, q.to().plusDays(1).toString());
        if (Boolean.TRUE.equals(q.topOnly())) w.isNull(DevAlarm::getParentAlarmNo);
        w.orderByDesc(DevAlarm::getOccurredAt).orderByDesc(DevAlarm::getId);
        Page<DevAlarm> r = mapper.selectPage(new Page<>(norm(q.page(), 1), normSize(q.size())), w);
        return new PageResult<>(r.getRecords().stream().map(AlarmServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmDetail detail(String alarmNo) {
        DevAlarm e = selectByNo(alarmNo);   // 带范围读
        ai.neargo.sharehub.alarm.entity.DevAlarmCode code = codeMapper.selectOne(new LambdaQueryWrapper<ai.neargo.sharehub.alarm.entity.DevAlarmCode>()
                .eq(ai.neargo.sharehub.alarm.entity.DevAlarmCode::getCode, e.getAlarmCode()).last("limit 1"));
        List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmLogItem> timeline = logMapper.selectList(
                        new LambdaQueryWrapper<ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog>()
                                .eq(ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog::getAlarmNo, alarmNo)
                                .orderByAsc(ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmLog::getId))
                .stream().map(l -> new ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmLogItem(l.getEvent(), l.getNote(), l.getOperator(), l.getCreatedAt()))
                .toList();
        List<AlarmRecord> recent = List.of();
        String subject = e.getSubjectNo() != null ? e.getSubjectNo() : e.getCabinetNo();
        if (subject != null) {
            recent = mapper.selectList(new LambdaQueryWrapper<DevAlarm>()
                            .and(x -> x.eq(DevAlarm::getSubjectNo, subject).or().eq(DevAlarm::getCabinetNo, subject))
                            .ne(DevAlarm::getAlarmNo, alarmNo)
                            .ge(DevAlarm::getOccurredAt, java.time.LocalDate.now().minusDays(7).toString())
                            .orderByDesc(DevAlarm::getId).last("limit 20"))
                    .stream().map(AlarmServiceImpl::toVO).toList();
        }
        return new ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmDetail(toVO(e), code == null ? null : code.getMessage(),
                code == null ? null : code.getSuggestion(), e.getEvidence(), timeline, recent);
    }

    @Override
    public ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmSummary summary() {
        java.util.Map<String, ai.neargo.sharehub.alarm.dto.AlarmDtos.DomainCount> byDomain = new java.util.LinkedHashMap<>();
        java.util.Map<String, long[]> acc = new java.util.LinkedHashMap<>();
        for (DevAlarm a : mapper.selectList(new LambdaQueryWrapper<DevAlarm>()
                .select(DevAlarm::getDomain, DevAlarm::getLevel)
                .in(DevAlarm::getStatus, List.of(AlarmStatus.OPEN.name(), AlarmStatus.ACKED.name())).isNull(DevAlarm::getParentAlarmNo))) {
            long[] v = acc.computeIfAbsent(a.getDomain() == null ? ai.neargo.sharehub.alarm.AlarmDomain.AVAILABILITY.name() : a.getDomain(), k -> new long[2]);
            v[0]++;
            if (ai.neargo.sharehub.alarm.AlarmLevel.CRITICAL.name().equals(a.getLevel())) v[1]++;
        }
        acc.forEach((k, v) -> byDomain.put(k, new ai.neargo.sharehub.alarm.dto.AlarmDtos.DomainCount(v[0], v[1])));
        long disposedOpen = mapper.selectCount(new LambdaQueryWrapper<DevAlarm>()
                .in(DevAlarm::getStatus, List.of(AlarmStatus.OPEN.name(), AlarmStatus.ACKED.name())).isNotNull(DevAlarm::getDispositionRef));
        long healed = mapper.selectCount(new LambdaQueryWrapper<DevAlarm>().eq(DevAlarm::getStatus, AlarmStatus.CLOSED.name())
                .in(DevAlarm::getCloseReason, List.of(AlarmCloseReason.SELF_HEALED.name(), AlarmCloseReason.AUTO_FIXED.name()))
                .ge(DevAlarm::getClosedAt, java.time.LocalDate.now().atStartOfDay()));
        return new ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmSummary(byDomain, disposedOpen, healed);
    }

    @Override
    public List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute> routes(String code) {
        return routeMapper.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute>()
                        .eq(ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute::getAlarmCode, code)
                        .orderByAsc(ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute::getId))
                .stream().map(r -> new ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute(r.getAlarmCode(), r.getCause(),
                        r.getDisposition(), r.getWoType(), r.getPriorityDelta(), r.getFallback())).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public List<ai.neargo.sharehub.alarm.dto.AlarmDtos.AlarmRoute> saveRoutes(String code,
            List<ai.neargo.sharehub.alarm.dto.AlarmDtos.RouteReq> reqs) {
        if (codeMapper.selectCount(new LambdaQueryWrapper<ai.neargo.sharehub.alarm.entity.DevAlarmCode>()
                .eq(ai.neargo.sharehub.alarm.entity.DevAlarmCode::getCode, code)) == 0) throw BizException.notFound(code);
        List<ai.neargo.sharehub.alarm.dto.AlarmDtos.RouteReq> in = reqs == null ? List.of() : reqs;
        java.util.Set<String> causes = new java.util.HashSet<>();
        for (var r : in) {
            String cause = notBlank(r.cause()) ? r.cause().trim() : "*";
            if (!"*".equals(cause)) ai.neargo.sharehub.alarm.AlarmCause.of(cause);
            if (!causes.add(cause)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.duplicate_value", cause);
            ai.neargo.sharehub.alarm.AlarmDisposition d = ai.neargo.sharehub.alarm.AlarmDisposition.of(r.disposition());
            if (d == ai.neargo.sharehub.alarm.AlarmDisposition.WORK_ORDER && notBlank(r.woType())) {
                ai.neargo.sharehub.wo.ext.WorkOrderType.of(r.woType());
            }
            if (notBlank(r.fallback())) ai.neargo.sharehub.alarm.AlarmDisposition.of(r.fallback());
        }
        // 整体替换：物理删旧路由（全局配置表，无业务历史意义；改动经审计拦截器留痕）
        routeMapper.delete(new LambdaQueryWrapper<ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute>()
                .eq(ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute::getAlarmCode, code));
        for (var r : in) {
            var e = new ai.neargo.sharehub.alarm.entity.AlarmEngineEntities.DevAlarmRoute();
            e.setAlarmCode(code);
            e.setCause(notBlank(r.cause()) ? r.cause().trim() : "*");
            e.setDisposition(ai.neargo.sharehub.alarm.AlarmDisposition.of(r.disposition()).name());
            e.setWoType(notBlank(r.woType()) ? r.woType().trim().toUpperCase() : null);
            e.setPriorityDelta(r.priorityDelta() == null ? 0 : Math.max(-2, Math.min(2, r.priorityDelta())));
            e.setFallback(notBlank(r.fallback()) ? r.fallback().trim().toUpperCase() : null);
            routeMapper.insert(e);
        }
        return routes(code);
    }

    @Override
    public List<ai.neargo.sharehub.alarm.dto.AlarmDtos.CodeStat> codeStats(int days) {
        java.util.Map<String, long[]> acc = new java.util.TreeMap<>();
        List<DevAlarm> rows = mapper.selectList(new LambdaQueryWrapper<DevAlarm>()
                .select(DevAlarm::getAlarmCode, DevAlarm::getCloseReason, DevAlarm::getDispositionType, DevAlarm::getDispositionRef)
                .ge(DevAlarm::getOccurredAt, java.time.LocalDate.now().minusDays(Math.max(1, Math.min(days, 365))).toString()));
        // 撤单率 = 开了工单、工单后来因告警自动恢复被撤（WITHDRAWN）的占比 —— 高了说明开单延迟太短，白跑了人
        java.util.Map<String, String> woClose = workOrders.closeReasons(rows.stream()
                .filter(a -> ai.neargo.sharehub.alarm.AlarmDisposition.WORK_ORDER.name().equals(a.getDispositionType()) && a.getDispositionRef() != null)
                .map(DevAlarm::getDispositionRef).distinct().toList());
        for (DevAlarm a : rows) {
            long[] v = acc.computeIfAbsent(a.getAlarmCode(), k -> new long[4]);
            if (a.getDispositionRef() != null && "WITHDRAWN".equals(woClose.get(a.getDispositionRef()))) v[3]++;
            v[0]++;
            if (AlarmCloseReason.FALSE_ALARM.name().equals(a.getCloseReason())) v[1]++;
            if (AlarmCloseReason.SELF_HEALED.name().equals(a.getCloseReason()) || AlarmCloseReason.AUTO_FIXED.name().equals(a.getCloseReason())) v[2]++;
        }
        return acc.entrySet().stream().map(en -> {
            long[] v = en.getValue();
            return new ai.neargo.sharehub.alarm.dto.AlarmDtos.CodeStat(en.getKey(), v[0], ratio(v[1], v[0]), ratio(v[2], v[0]), ratio(v[3], v[0]));
        }).toList();
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0d : Math.round(a * 1000.0 / b) / 1000.0;
    }

    @Override
    public List<AlarmRecord> byWorkOrder(String woNo) {
        // wo_no 是旧列（设备告警转单写它），disposition_ref 是新列 —— 两处都认
        return mapper.selectList(new LambdaQueryWrapper<DevAlarm>()
                        .and(x -> x.eq(DevAlarm::getDispositionRef, woNo).or().eq(DevAlarm::getWoNo, woNo))
                        .orderByAsc(DevAlarm::getId))
                .stream().map(AlarmServiceImpl::toVO).toList();
    }

    @Override
    public java.util.Map<String, Integer> countByWorkOrders(java.util.Collection<String> woNos) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        if (woNos == null || woNos.isEmpty()) return out;
        for (DevAlarm a : mapper.selectList(new LambdaQueryWrapper<DevAlarm>()
                .select(DevAlarm::getWoNo, DevAlarm::getDispositionRef)
                .and(x -> x.in(DevAlarm::getDispositionRef, woNos).or().in(DevAlarm::getWoNo, woNos)))) {
            String no = a.getDispositionRef() != null && woNos.contains(a.getDispositionRef()) ? a.getDispositionRef() : a.getWoNo();
            if (no != null) out.merge(no, 1, Integer::sum);
        }
        return out;
    }
}
