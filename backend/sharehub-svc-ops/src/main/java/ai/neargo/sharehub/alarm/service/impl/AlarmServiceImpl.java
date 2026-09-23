package ai.neargo.sharehub.alarm.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.alarm.AlarmStateMachine;
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

    public AlarmServiceImpl(DevAlarmMapper mapper, DevAlarmNoticeMapper noticeMapper,
                            AlarmStateMachine stateMachine) {
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
    public WorkOrderRef toWorkOrder(String alarmNo) {
        DevAlarm e = selectByNo(alarmNo);

        // —— 幂等：alarmNo 是幂等键 ——
        // wo_no 非空即表示此告警已开过单，直接返回首次生成的单号，不再建第二张。
        // 这是幂等的**快路径**；最终硬约束在 wo_order.source_ref 的 UNIQUE(alarmNo) 上（db-design §1.6），
        // 并发下两个请求可能同时看到 wo_no 为空，那时由 UNIQUE 兜底、建单方捕获 DuplicateKeyException 后回读。
        if (notBlank(e.getWoNo())) {
            return new WorkOrderRef(e.getAlarmNo(), e.getWoNo(), false);
        }

        // TODO(跨分片依赖 · 工单域)：调 wo 域建单
        //   WorkOrderService.createFromAlarm(sourceRef = alarmNo, type = FAULT,
        //       cabinetNo = e.getCabinetNo(), priority ← e.getLevel(),
        //       description ← dev_alarm_code.message + suggestion)
        //   建单方必须把 alarmNo 写进 wo_order.source_ref（UNIQUE），并在 DuplicateKeyException 时
        //   按 source_ref 回读既有 woNo 返回 —— 幂等语义由「本方法的快路径 + 那条 UNIQUE」共同保证。
        //   建单成功后回填 e.setWoNo(woNo) + mapper.updateById(e)。
        //   工单域归其他分片，此处不跨包直连 wo mapper，避免两域耦合成环。
        throw new UnsupportedOperationException(
                "告警转工单待接工单域 service（wo 分片）：alarmNo=" + alarmNo);
    }

    // ——————————————————————— 内部 ———————————————————————

    private DevAlarm selectByNo(String alarmNo) {
        DevAlarm e = mapper.selectOne(new LambdaQueryWrapper<DevAlarm>()
                .eq(DevAlarm::getAlarmNo, alarmNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("告警不存在: " + alarmNo);
        return e;
    }

    private static AlarmRecord toVO(DevAlarm e) {
        // siteName 需 join loc_site 取名；场地域归其他分片，先出 siteNo，前端可先按号展示。
        return new AlarmRecord(e.getAlarmNo(), e.getCabinetNo(), e.getSiteNo(), null,
                e.getAgentNo(), e.getVendorCode(), e.getAlarmCode(), e.getVendorErrorCode(),
                e.getLevel(), e.getSource(), e.getOccurredAt(), e.getStatus(),
                e.getWoNo(), e.getRemark(), e.getDedupKey(), e.getCount());
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
                .in(DevAlarm::getStatus, List.of(AlarmStateMachine.OPEN, AlarmStateMachine.ACKED));
        java.util.List<DevAlarm> pending = mapper.selectList(w);
        int n = 0;
        for (DevAlarm a : pending) {
            // 开单编号先占位：真正的工单创建走 wo 域（跨服务），此处只标记已开单。
            // 拆分后这里改为发事件，由 ops 服务消费建单。
            a.setWoNo("WO-AUTO-" + a.getAlarmNo());
            mapper.updateById(a);
            n++;
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
        if (src == null) throw new IllegalArgumentException("告警通知不存在: " + noticeNo);

        // 重发 = 新行指回源行（resend_of），不改写源行 —— 发送历史是审计事实
        DevAlarmNotice e = new DevAlarmNotice();
        e.setNoticeNo(ai.neargo.common.core.IdGenerator.next(ai.neargo.sharehub.common.BizKey.ALARM_NOTICE));
        e.setTenantId(src.getTenantId());
        e.setAlarmNo(src.getAlarmNo());
        e.setChannel(src.getChannel());
        e.setTarget(src.getTarget());
        e.setSentAt(java.time.LocalDateTime.now().toString());
        e.setStatus("SENT");   // 触达通道未接（notify 域 stub），先落 SENT 占位；接通后回写真实回执
        e.setIdempotencyKey(idempotencyKey);
        e.setResendOf(noticeNo);
        noticeMapper.insert(e);
        return toNoticeVO(e);
    }
}
