package ai.neargo.sharehub.wo.ext.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.wo.WoStateMachine;
import ai.neargo.sharehub.wo.entity.WoOrder;
import ai.neargo.sharehub.wo.ext.WorkOrderType;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AcceptReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CloseReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.HandleReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.RejectReq;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDraft;
import ai.neargo.sharehub.wo.ext.entity.WoDispatch;
import ai.neargo.sharehub.wo.ext.entity.WoHandle;
import ai.neargo.sharehub.wo.ext.entity.WoSla;
import ai.neargo.sharehub.wo.ext.entity.WoSlaRule;
import ai.neargo.sharehub.wo.ext.mapper.WoDispatchMapper;
import ai.neargo.sharehub.wo.ext.mapper.WoHandleMapper;
import ai.neargo.sharehub.wo.ext.mapper.WoSlaMapper;
import ai.neargo.sharehub.wo.ext.mapper.WoSlaRuleMapper;
import ai.neargo.sharehub.wo.ext.service.WoOpsService;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 工单流转扩展实现。
 *
 * <p><b>边界说明</b>：{@link WoMapper} / {@link WoOrder} / {@link WoStateMachine} 属 {@code wo} 主包，
 * 本类**只使用不修改**。{@link WoOrder} 目前没有 {@code sourceRef} / {@code closeReason} /
 * {@code agentNo} / {@code siteNo} / {@code assigneeNo} 等 [db-design §3.6] 要求的列对应字段
 * （见交付报告），对这几列的写入改用按列名的 {@link UpdateWrapper} —— 不新增实体字段、不动主包文件。
 */
@Service
public class WoOpsServiceImpl implements WoOpsService {

    /** [db-design §9A.4] 关单原因取值域。 */
    private static final Set<String> CLOSE_REASONS =
            Set.of("RESOLVED", "INVALID", "DUPLICATE", "WITHDRAWN");

    private static final Set<String> SOURCES = Set.of("ALERT", "USER", "VENUE", "MANUAL", "PLAN");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");
    private static final String TENANT_MAIN = "MAIN";

    private final WoMapper woMapper;
    private final WoStateMachine stateMachine;
    private final WoDispatchMapper dispatchMapper;
    private final WoHandleMapper handleMapper;
    private final WoSlaMapper slaMapper;
    private final WoSlaRuleMapper slaRuleMapper;

    public WoOpsServiceImpl(WoMapper woMapper, WoStateMachine stateMachine,
                            WoDispatchMapper dispatchMapper, WoHandleMapper handleMapper,
                            WoSlaMapper slaMapper, WoSlaRuleMapper slaRuleMapper) {
        this.woMapper = woMapper;
        this.stateMachine = stateMachine;
        this.dispatchMapper = dispatchMapper;
        this.handleMapper = handleMapper;
        this.slaMapper = slaMapper;
        this.slaRuleMapper = slaRuleMapper;
    }

    // ——————————————————————— 开单 ———————————————————————

    @Override
    @Transactional
    public WorkOrder create(WorkOrderDraft draft) {
        if (draft == null) throw new IllegalArgumentException("开单入参必填");

        String type = WorkOrderType.of(draft.type()).name();
        String source = (draft.source() == null || draft.source().isBlank()) ? "MANUAL" : draft.source();
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("工单来源非法: " + source);
        String priority = (draft.priority() == null || draft.priority().isBlank()) ? "MEDIUM" : draft.priority();
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("工单优先级非法: " + priority);

        // 幂等（[db-design §1.6]）：告警/投诉转工单重复点「转工单」应返回首次结果，不产生第二张单。
        // 落点是 wo_order.source_ref 上的 UNIQUE。手工开单 source_ref 为空，不参与幂等。
        String sourceRef = draft.sourceNo();
        if (sourceRef != null && !sourceRef.isBlank()) {
            WoOrder dup = woMapper.selectOne(new QueryWrapper<WoOrder>()
                    .eq("source_ref", sourceRef).last("limit 1"));
            if (dup != null) return toVO(dup);
        }

        WoOrder e = new WoOrder();
        e.setWoNo(IdGenerator.next(BizKey.WORK_ORDER));
        e.setTenantId(TENANT_MAIN);
        e.setType(type);
        e.setSource(source);
        e.setPriority(priority);
        e.setCabinetNo(draft.cabinetNo());
        e.setLocationName(draft.locationName());
        e.setDescription(draft.description());
        e.setStatus("CREATED");
        e.setWoCreatedAt(LocalDateTime.now().toString());

        // 按 SLA 规则算 due 时刻，落逐单计时行；顺带把 sla_due_at 冗余到工单上供列表直出
        WoSlaRule rule = activeRule(type);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime respondDue = (rule == null || rule.getResponseMins() == null || rule.getResponseMins() <= 0)
                ? null : now.plusMinutes(rule.getResponseMins());
        LocalDateTime resolveDue = (rule == null || rule.getResolveMins() == null || rule.getResolveMins() <= 0)
                ? null : now.plusMinutes(rule.getResolveMins());
        if (resolveDue != null) e.setSlaDueAt(resolveDue.toString());

        woMapper.insert(e);

        // source_ref / agent_no / site_no 不是 WoOrder 实体的字段（wo 主包边界），按列名补写。
        // 与 insert 同事务；并发下的重复转单由 source_ref 的 UNIQUE 在此处抛出（DuplicateKeyException）。
        UpdateWrapper<WoOrder> extra = new UpdateWrapper<>();
        extra.eq("wo_no", e.getWoNo());
        boolean anyExtra = false;
        if (sourceRef != null && !sourceRef.isBlank()) { extra.set("source_ref", sourceRef); anyExtra = true; }
        if (notBlank(draft.agentNo())) { extra.set("agent_no", draft.agentNo()); anyExtra = true; }
        if (notBlank(draft.siteNo())) { extra.set("site_no", draft.siteNo()); anyExtra = true; }
        if (notBlank(draft.locationNo())) { extra.set("location_no", draft.locationNo()); anyExtra = true; }
        if (notBlank(draft.expectedAt())) { extra.set("expected_at", draft.expectedAt()); anyExtra = true; }
        if (anyExtra) woMapper.update(null, extra);

        WoSla sla = new WoSla();
        sla.setWoNo(e.getWoNo());
        sla.setRespondDueAt(respondDue == null ? null : respondDue.toString());
        sla.setResolveDueAt(resolveDue == null ? null : resolveDue.toString());
        sla.setRespondBreached(0);
        sla.setResolveBreached(0);
        slaMapper.insert(sla);   // 规则→计时的转换只发生一次；此后改规则不回溯本单（见 WoSla 类注释）

        return toVO(e);
    }

    // ——————————————————————— 接单 ———————————————————————

    @Override
    @Transactional
    public WorkOrder accept(String woNo, AcceptReq req) {
        WoOrder e = byNo(woNo);
        e.setStatus(stateMachine.next(e.getStatus(), "ACCEPT"));   // DISPATCHED→ACCEPTED，非法迁移由状态机拒
        woMapper.updateById(e);

        // 与派单/驳回共用 append 表：同一根「这单转了几手」的时间轴
        appendDispatch(woNo, resolveAssignee(req == null ? null : req.assigneeNo(), e), "ACCEPT");

        // ACCEPTED 是响应 SLA 的度量终点（[db-design §9A.4]）—— 这就是该状态不能被删掉的原因
        markBreached(woNo, true);
        return toVO(e);
    }

    // ——————————————————————— 现场处理 ———————————————————————

    @Override
    @Transactional
    public WorkOrder handle(String woNo, HandleReq req) {
        WoOrder e = byNo(woNo);

        // PROCESSING 上的重复提交是自环（前端 WO_TRANSITIONS.process），只留痕不改状态，
        // 故不喂给状态机 —— 状态机里没有自环边，喂进去会被当成非法迁移拒掉。
        if (!"PROCESSING".equals(e.getStatus())) {
            e.setStatus(stateMachine.next(e.getStatus(), "PROCESS"));   // ACCEPTED→PROCESSING
            woMapper.updateById(e);
        }

        appendHandle(e, req, null);
        return toVO(e);
    }

    // ——————————————————————— 完工 ———————————————————————

    /**
     * 完工：{@code PROCESSING → DONE}（状态机 {@code DONE} 事件），并落一行 {@code wo_handle} 作为完工记录。
     *
     * <p><b>为什么不复用 {@link #handle}</b>：handle 在 PROCESSING 上是自环（提交处理进展，可多次），
     * complete 是把工单推到 DONE 交给验收（一次）。两者共用一个端点的话，
     * 「又提交了一次进展」和「报完工了」在数据上无从区分，验收队列也就无从生成。
     * 共用的只有留痕写法，抽成 {@link #appendHandle}。
     */
    @Override
    @Transactional
    public WorkOrder complete(String woNo, HandleReq req) {
        WoOrder e = byNo(woNo);
        // 起点必须是 PROCESSING；CREATED/DISPATCHED/ACCEPTED（还没开工）与 DONE/AUDITED/CLOSED
        // （已经报过完工）都由状态机拒 → 400。不在这里补 if，报错口径统一走状态机。
        e.setStatus(stateMachine.next(e.getStatus(), "DONE"));
        woMapper.updateById(e);

        appendHandle(e, req, "COMPLETE");
        return toVO(e);
    }

    // ——————————————————————— 关单 ———————————————————————

    /**
     * 审核关单：{@code DONE → AUDITED → CLOSED}。
     *
     * <p>逐步走状态机而不是一步设成 {@code CLOSED}：两条边各有各的语义（验收判定 / 归档），
     * 跳过中间态会让 {@code AUDITED} 永远为空，[db-design §9A.4] 保留该态的理由
     * （完工人 ≠ 验收人）就落空了。
     *
     * <p><b>2026-07-30 收紧</b>：原实现允许从 {@code PROCESSING} 起一路走到 CLOSED
     * —— 那是 {@code /complete} 端点还不存在时的权宜。现在完工有了自己的端点，
     * 继续放行等于给验收人开了一条「处理人从没报完工，我直接关掉」的后门，
     * 「完工人 ≠ 验收人」这条合规要求会被静默绕过。故起点只接受 DONE
     * （AUDITED 也接受：那是上一次关单在中途失败留下的半成品，允许续走完成归档）。
     */
    @Override
    @Transactional
    public WorkOrder close(String woNo, CloseReq req) {
        // 前端 WorkOrderClosePayload 只传验收三件套：closeReason 缺省时由 auditResult 推导
        // （PASS/PASS_WITH_ISSUE→RESOLVED）；FAIL 不许直接关单 —— 那是 /rework 的活。
        String closeReason = req == null ? null : req.closeReason();
        String auditResult = req == null ? null : req.auditResult();
        if ((closeReason == null || closeReason.isBlank()) && notBlank(auditResult)) {
            if ("FAIL".equals(auditResult)) {
                throw new IllegalArgumentException("验收不合格（FAIL）不允许关单，请走 /rework 退回返工");
            }
            if ("PASS".equals(auditResult) || "PASS_WITH_ISSUE".equals(auditResult)) {
                closeReason = "RESOLVED";
            }
        }
        if (closeReason == null || closeReason.isBlank()) {
            // [db-design §9A.4]：关单必须给结论，空则拒。没有 close_reason 的 CLOSED 单
            // 事后无法区分「修好了」和「误报撤单」，达标率与告警质量两个指标都会被污染。
            throw new IllegalArgumentException("关单原因 closeReason 必填（RESOLVED/INVALID/DUPLICATE/WITHDRAWN），或传验收结论 auditResult");
        }
        if (!CLOSE_REASONS.contains(closeReason)) {
            throw new IllegalArgumentException("关单原因非法: " + closeReason
                    + "（仅 RESOLVED/INVALID/DUPLICATE/WITHDRAWN）");
        }

        WoOrder e = byNo(woNo);
        // 从当前态一路走到 CLOSED；起点不在 DONE/AUDITED 上的由状态机拒（含 PROCESSING —— 需先 /complete）
        for (String event : List.of("AUDIT", "CLOSE")) {
            if ("CLOSED".equals(e.getStatus())) break;
            if (isBefore(e.getStatus(), event)) {
                e.setStatus(stateMachine.next(e.getStatus(), event));
            }
        }
        if (!"CLOSED".equals(e.getStatus())) {
            // 兜底：上面的链没能走到终点说明起点非法，交给状态机给出统一的报错口径
            e.setStatus(stateMachine.next(e.getStatus(), "CLOSE"));
        }
        woMapper.updateById(e);

        // close_reason / 验收四件套不是 WoOrder 实体的字段（wo 主包边界），按列名补写。
        // audited_by：入参显式指定优先，否则记当前登录人 —— 验收人是合规字段，不能为空。
        String auditor = notBlank(req.auditorNo()) ? req.auditorNo()
                : SecurityUtils.currentUser().map(LoginUser::username).orElse("system");
        UpdateWrapper<WoOrder> uw = new UpdateWrapper<WoOrder>()
                .eq("wo_no", woNo)
                .set("close_reason", closeReason)
                .set("closed_at", LocalDateTime.now())
                .set("audited_by", auditor)
                .set("audited_at", LocalDateTime.now());
        if (notBlank(auditResult)) uw.set("audit_result", auditResult);
        if (notBlank(req.auditNote())) uw.set("audit_note", req.auditNote());
        woMapper.update(null, uw);

        markBreached(woNo, false);
        return toVO(e);
    }

    // ——————————————————————— 派单（编排主包迁移 + 时间轴留痕）———————————————————————

    @Override
    @Transactional
    public WorkOrder dispatch(String woNo, String assignee) {
        WoOrder e = byNo(woNo);
        e.setStatus(stateMachine.next(e.getStatus(), "DISPATCH"));   // CREATED→DISPATCHED，非法迁移拒
        e.setAssigneeName(assignee);
        woMapper.updateById(e);
        appendDispatch(woNo, resolveAssignee(assignee, e), "DISPATCH");
        return toVO(e);
    }

    // ——————————————————————— 列表（富行装配）———————————————————————

    @Override
    public ai.neargo.common.core.PageResult<WorkOrder> pageRich(Integer page, Integer size,
                                                                String keyword, String status, String type) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<WoOrder> w = new LambdaQueryWrapper<>();
        if (notBlank(keyword)) {
            w.and(q -> q.like(WoOrder::getWoNo, keyword).or().like(WoOrder::getCabinetNo, keyword));
        }
        if (notBlank(status)) w.eq(WoOrder::getStatus, status);
        if (notBlank(type)) w.eq(WoOrder::getType, type);
        w.orderByAsc(WoOrder::getId);
        var r = woMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, s), w);
        List<WoOrder> rows = r.getRecords();
        if (rows.isEmpty()) return new ai.neargo.common.core.PageResult<>(List.of(), r.getTotal());
        List<String> nos = rows.stream().map(WoOrder::getWoNo).toList();

        // wo_order 扩展列（实体外，按列名批取；DATETIME 值 toString 归一为 ISO 文本）
        java.util.Map<String, java.util.Map<String, Object>> extras = new java.util.HashMap<>();
        for (java.util.Map<String, Object> m : woMapper.selectMaps(new QueryWrapper<WoOrder>()
                .select("wo_no", "source_ref", "expected_at", "reject_reason", "reject_count",
                        "audited_by", "audited_at", "audit_result", "audit_note")
                .in("wo_no", nos))) {
            extras.put(String.valueOf(m.get("wo_no")), m);
        }
        // dispatch 时间轴：按插入序扫，天然取到每单各 action 的最新一行
        java.util.Map<String, String> dispatchedAt = new java.util.HashMap<>();
        java.util.Map<String, WoDispatch> accepted = new java.util.HashMap<>();
        for (WoDispatch d : dispatchMapper.selectList(new LambdaQueryWrapper<WoDispatch>()
                .in(WoDispatch::getWoNo, nos).orderByAsc(WoDispatch::getId))) {
            if ("DISPATCH".equals(d.getAction())) dispatchedAt.put(d.getWoNo(), d.getDispatchedAt());
            if ("ACCEPT".equals(d.getAction())) accepted.put(d.getWoNo(), d);
        }
        // handle 时间轴：最新一行（现场处理）+ 最新完工行（note 前缀 COMPLETE，见 appendHandle）
        java.util.Map<String, WoHandle> lastHandle = new java.util.HashMap<>();
        java.util.Map<String, String> completedAt = new java.util.HashMap<>();
        for (WoHandle h : handleMapper.selectList(new LambdaQueryWrapper<WoHandle>()
                .in(WoHandle::getWoNo, nos).orderByAsc(WoHandle::getId))) {
            lastHandle.put(h.getWoNo(), h);
            if (h.getNote() != null && h.getNote().startsWith("COMPLETE")) {
                completedAt.put(h.getWoNo(), h.getHandledAt());
            }
        }

        List<WorkOrder> out = rows.stream().map(e -> {
            java.util.Map<String, Object> x = extras.getOrDefault(e.getWoNo(), java.util.Map.of());
            WoDispatch acc = accepted.get(e.getWoNo());
            WoHandle h = lastHandle.get(e.getWoNo());
            return new WorkOrder(e.getWoNo(), e.getType(), e.getSource(), e.getPriority(), e.getCabinetNo(),
                    e.getLocationName(), e.getStatus(), e.getAssigneeName(), e.getSlaDueAt(),
                    e.getDescription(), e.getWoCreatedAt(),
                    str(x.get("source_ref")), str(x.get("expected_at")),
                    dispatchedAt.get(e.getWoNo()),
                    acc == null ? null : acc.getDispatchedAt(),
                    h != null ? h.getAssigneeNo() : (acc == null ? null : acc.getAssigneeNo()),
                    h == null ? null : h.getHandledAt(),
                    h == null ? null : h.getNote(),
                    h != null && h.getPartChanged() != null && h.getPartChanged() == 1 ? "PART_CHANGED" : null,
                    completedAt.get(e.getWoNo()),
                    str(x.get("audited_by")), str(x.get("audited_at")),
                    str(x.get("audit_result")), str(x.get("audit_note")),
                    str(x.get("reject_reason")),
                    x.get("reject_count") == null ? 0 : ((Number) x.get("reject_count")).intValue());
        }).toList();
        return new ai.neargo.common.core.PageResult<>(out, r.getTotal());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ——————————————————————— 回退：驳回 / 返工 ———————————————————————

    /**
     * 驳回退回待派单：{@code DISPATCHED/ACCEPTED/PROCESSING → CREATED}。
     *
     * <p>清空 {@code assignee_no}：回到 CREATED 就是「这单还没人管」，
     * 留着上一个受理人会让待派单列表显示一个并不负责的名字，派单人据此判断必然出错。
     */
    @Override
    @Transactional
    public WorkOrder reject(String woNo, RejectReq req) {
        String reason = requireReason(req, "驳回");
        WoOrder e = byNo(woNo);
        String rejected = resolveAssignee(null, e);   // 先记下被退回的是谁，再清空
        e.setStatus(stateMachine.next(e.getStatus(), "REJECT"));  // 非法起点（CREATED/DONE/…）由状态机拒 → 400
        e.setAssigneeName(null);
        woMapper.updateById(e);

        // reject_reason 不是 WoOrder 实体的字段（wo 主包边界，见类注释），按列名补写
        woMapper.update(null, new UpdateWrapper<WoOrder>()
                .eq("wo_no", woNo)
                .set("reject_reason", reason)
                .setSql("reject_count = COALESCE(reject_count, 0) + 1"));

        // 与派单/接单共用 append 表：退回也是「转手」的一环，缺了它时间轴上会凭空断一截。
        // 记的是「被退回的那次指派是谁」，不是空 —— 否则事后看不出这单是从谁手里收回来的。
        appendDispatch(woNo, rejected, "REJECT");
        return toVO(e);
    }

    /**
     * 验收不合格退回返工：{@code DONE → PROCESSING}，受理人**保留**。
     *
     * <p>原因落 {@code wo_handle.note}（而非 {@code wo_dispatch}）：返工是给处理人的指令，
     * 它要出现在处理记录的时间轴上，让处理人打开工单就能看到「上次为什么没通过」。
     * 同时写 {@code reject_reason} 供列表直出最近一次退回原因。
     */
    @Override
    @Transactional
    public WorkOrder rework(String woNo, RejectReq req) {
        String reason = requireReason(req, "退回返工");
        WoOrder e = byNo(woNo);
        e.setStatus(stateMachine.next(e.getStatus(), "REWORK"));  // 只接受 DONE 起点，其余由状态机拒 → 400
        woMapper.updateById(e);

        woMapper.update(null, new UpdateWrapper<WoOrder>()
                .eq("wo_no", woNo)
                .set("reject_reason", reason)
                .setSql("reject_count = COALESCE(reject_count, 0) + 1"));

        WoHandle h = new WoHandle();
        h.setWoNo(woNo);
        h.setAssigneeNo(resolveAssignee(null, e));
        h.setNote("REWORK: " + reason);
        h.setPartChanged(0);
        h.setDeviceChanged(0);
        h.setHandledAt(LocalDateTime.now().toString());
        handleMapper.insert(h);

        return toVO(e);
    }

    // ——————————————————————— 内部 ———————————————————————

    /** 回退原因必填校验；空则 400（见 {@code RejectReq} 注释）。 */
    private static String requireReason(RejectReq req, String action) {
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException(action + "必须填写原因 reason");
        }
        return req.reason().trim();
    }

    /**
     * 受理人兜底解析。{@code wo_dispatch.assignee_id} / {@code wo_handle.assignee_id} 都是
     * <b>NOT NULL 且无默认值</b>（[db-design §3.6] 的 DDL，V4），写 null 会直接
     * {@code Field 'assignee_id' doesn't have a default value} → 500。
     *
     * <p>而 ops-web 的两个入参形状（{@code acceptWorkOrder(no, handler)} /
     * {@code WorkOrderHandlePayload}）**都不含 assigneeNo** —— 也就是说 {@code /accept}
     * 与 {@code /handle} 在真实前端调用下必然 500（本轮补 {@code /complete} 时被测试撞出来）。
     * 兜底链：入参 → 工单当前受理人 → 当前登录人 → {@code "system"}。
     * 「谁提交的就记谁」在留痕语义上是成立的，也比 500 有用得多。
     */
    private static String resolveAssignee(String given, WoOrder e) {
        if (notBlank(given)) return given.trim();
        if (e != null && notBlank(e.getAssigneeName())) return e.getAssigneeName();
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("system");
    }

    /** 落一行 {@code wo_handle}（handle 自环留痕与 complete 完工记录共用）。 */
    private void appendHandle(WoOrder e, HandleReq req, String notePrefix) {
        String woNo = e.getWoNo();
        WoHandle h = new WoHandle();
        h.setWoNo(woNo);
        h.setAssigneeNo(resolveAssignee(req == null ? null : req.assigneeNo(), e));
        h.setPhotos(req == null ? null : req.photos());
        String note = req == null ? null : req.handleNote();
        h.setNote(notePrefix == null ? note : notePrefix + ": " + (note == null ? "" : note));
        h.setPartChanged(bool(req == null ? null : req.partChanged()));
        h.setDeviceChanged(bool(req == null ? null : req.deviceChanged()));
        h.setHandledAt(LocalDateTime.now().toString());
        handleMapper.insert(h);
    }

    /** 落一行 {@code wo_dispatch}（派单/接单/驳回共用同一根「转了几手」的时间轴）。 */
    private void appendDispatch(String woNo, String assigneeNo, String action) {
        WoDispatch d = new WoDispatch();
        d.setWoNo(woNo);
        d.setAssigneeNo(assigneeNo);
        d.setStrategy("MANUAL");
        d.setAction(action);
        d.setDispatchedAt(LocalDateTime.now().toString());
        dispatchMapper.insert(d);
    }

    /** 当前状态是否还在该事件之前（即该事件是否需要执行）。 */
    private static boolean isBefore(String status, String event) {
        return switch (event) {
            case "AUDIT" -> "DONE".equals(status);
            case "CLOSE" -> "AUDITED".equals(status);
            default -> false;
        };
    }

    private WoOrder byNo(String woNo) {
        if (woNo == null || woNo.isBlank()) throw new IllegalArgumentException("woNo 必填");
        WoOrder e = woMapper.selectOne(new LambdaQueryWrapper<WoOrder>()
                .eq(WoOrder::getWoNo, woNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("工单不存在: " + woNo);
        return e;
    }

    /** 取该工单类型当前启用的 SLA 规则；没有配置则不计时（due 为空，永不超时）。 */
    private WoSlaRule activeRule(String woType) {
        return slaRuleMapper.selectOne(new LambdaQueryWrapper<WoSlaRule>()
                .eq(WoSlaRule::getWoType, woType)
                .eq(WoSlaRule::getActive, 1)
                .orderByDesc(WoSlaRule::getId)
                .last("limit 1"));
    }

    /**
     * 判定并落超时标记。
     *
     * @param respond true=判响应（接单时刻 vs {@code respond_due_at}），false=判解决（关单时刻 vs {@code resolve_due_at}）
     */
    private void markBreached(String woNo, boolean respond) {
        WoSla sla = slaMapper.selectOne(new LambdaQueryWrapper<WoSla>()
                .eq(WoSla::getWoNo, woNo).last("limit 1"));
        if (sla == null) return;
        String due = respond ? sla.getRespondDueAt() : sla.getResolveDueAt();
        if (due == null || due.isBlank()) return;      // 无规则配置 = 不考核
        boolean breached = LocalDateTime.now().isAfter(LocalDateTime.parse(due));
        if (!breached) return;
        if (respond) sla.setRespondBreached(1); else sla.setResolveBreached(1);
        slaMapper.updateById(sla);
    }

    private static Integer bool(Boolean b) {
        return (b != null && b) ? 1 : 0;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static WorkOrder toVO(WoOrder e) {
        return new WorkOrder(e.getWoNo(), e.getType(), e.getSource(), e.getPriority(), e.getCabinetNo(),
                e.getLocationName(), e.getStatus(), e.getAssigneeName(), e.getSlaDueAt(),
                e.getDescription(), e.getWoCreatedAt());
    }
}
