package ai.neargo.sharehub.wo.ext.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.wo.dto.WoDtos.WorkOrder;
import ai.neargo.sharehub.wo.WoStateMachine;
import ai.neargo.sharehub.wo.WoDispatchAction;
import ai.neargo.sharehub.wo.WorkOrderStatus;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WoOpsServiceImpl.class);

    /** [db-design §9A.4] 关单原因取值域。 */
    private static final Set<String> CLOSE_REASONS =
            Set.of("RESOLVED", "INVALID", "DUPLICATE", "WITHDRAWN");

    private static final Set<String> SOURCES = Set.of("ALERT", "USER", "VENUE", "MANUAL", "PLAN", "INSPECTION");
    /** 巡检能派生的工单类型：现场能判断、且需要另派人 / 另排时间处理的。 */
    private static final Set<String> DERIVABLE = Set.of(WorkOrderType.FAULT.name(), WorkOrderType.REFILL.name(), WorkOrderType.CLEAN.name());
    private static final Set<String> INSPECTING = Set.of(WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name(), WorkOrderStatus.DONE.name());
    private static final String TENANT_MAIN = "MAIN";
    private static final String WO_REVIEW_FAILED = ai.neargo.sharehub.wo.WoReviewStatus.FAILED.name();
    private static final String SYSTEM = "SYSTEM";
    /** 自动派单的「运维主管」角色：无责任人可派时通知他们。 */
    private static final Set<String> OPS_LEAD_ROLES = Set.of("OPS");

    private final WoMapper woMapper;
    private final WoStateMachine stateMachine;
    private final WoDispatchMapper dispatchMapper;
    private final WoHandleMapper handleMapper;
    private final WoSlaMapper slaMapper;
    private final WoSlaRuleMapper slaRuleMapper;
    /** 只用来反查机柜归属 —— 数据范围锚点必须服务端派生，见 create() 里的说明。 */
    private final CabinetQueryPort cabinetQuery;
    // —— 2026-09-25 承接业务告警 ——
    private final ai.neargo.sharehub.api.platform.port.SiteQueryPort siteQuery;
    private final ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agents;
    private final ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort employees;
    private final ai.neargo.sharehub.api.platform.port.FileBindingPort files;
    private final ai.neargo.sharehub.api.platform.port.NotifyPort notify;
    private final ai.neargo.sharehub.common.event.DomainEventBus events;
    private final ai.neargo.sharehub.api.platform.port.SysParamPort params;

    public WoOpsServiceImpl(WoMapper woMapper, WoStateMachine stateMachine,
                            WoDispatchMapper dispatchMapper, WoHandleMapper handleMapper,
                            WoSlaMapper slaMapper, WoSlaRuleMapper slaRuleMapper,
                            CabinetQueryPort cabinetQuery,
                            ai.neargo.sharehub.api.platform.port.SiteQueryPort siteQuery,
                            ai.neargo.sharehub.api.platform.port.AgentDirectoryPort agents,
                            ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort employees,
                            ai.neargo.sharehub.api.platform.port.FileBindingPort files,
                            ai.neargo.sharehub.api.platform.port.NotifyPort notify,
                            ai.neargo.sharehub.common.event.DomainEventBus events,
                            ai.neargo.sharehub.api.platform.port.SysParamPort params) {
        this.params = params;
        this.siteQuery = siteQuery;
        this.agents = agents;
        this.employees = employees;
        this.files = files;
        this.notify = notify;
        this.events = events;
        this.woMapper = woMapper;
        this.stateMachine = stateMachine;
        this.dispatchMapper = dispatchMapper;
        this.handleMapper = handleMapper;
        this.slaMapper = slaMapper;
        this.slaRuleMapper = slaRuleMapper;
        this.cabinetQuery = cabinetQuery;
    }

    // ——————————————————————— 开单 ———————————————————————

    @Override
    @Transactional
    public WorkOrder create(WorkOrderDraft draft) {
        if (draft == null) throw new IllegalArgumentException("开单入参必填");

        String type = WorkOrderType.of(draft.type()).name();
        String source = (draft.source() == null || draft.source().isBlank()) ? "MANUAL" : draft.source();
        if (!SOURCES.contains(source)) throw new IllegalArgumentException("工单来源非法: " + source);
        String priority = (draft.priority() == null || draft.priority().isBlank())
                ? ai.neargo.sharehub.wo.WoPriority.MEDIUM.name()
                : ai.neargo.sharehub.wo.WoPriority.of(draft.priority()).name();

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
        e.setStatus(WorkOrderStatus.CREATED.name());
        e.setWoCreatedAt(LocalDateTime.now().toString());
        if (sourceRef != null && !sourceRef.isBlank()) e.setSourceRef(sourceRef);   // 撞唯一键发生在 insert 本身（见实体注释）

        // 按 SLA 规则算 due 时刻，落逐单计时行；顺带把 sla_due_at 冗余到工单上供列表直出
        WoSlaRule rule = activeRule(type, priority);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime respondDue = (rule == null || rule.getResponseMins() == null || rule.getResponseMins() <= 0)
                ? null : now.plusMinutes(rule.getResponseMins());
        LocalDateTime resolveDue = (rule == null || rule.getResolveMins() == null || rule.getResolveMins() <= 0)
                ? null : now.plusMinutes(rule.getResolveMins());
        if (resolveDue != null) e.setSlaDueAt(resolveDue.toString());

        woMapper.insert(e);

        /*
         * source_ref / agent_no / site_no / location_no 不是 WoOrder 实体的字段
         * （wo 主包边界），按列名补写。与 insert 同事务；并发下的重复转单由
         * source_ref 的 UNIQUE 在此处抛出（DuplicateKeyException）。
         *
         * ⚠️ **归属三列一律从机柜反查，不采信 draft 传来的值。** 两个理由：
         *   · 它们是数据范围锚点 —— 由调用方决定「这单归谁看」，等于让调用方
         *     把工单塞进任意代理的视野，或者从该看到的人眼前藏起来；
         *   · ops-web 的 WorkOrderDraft 里压根没有这三个字段，于是从界面开的工单
         *     锚点一律为空 —— 代理看不到给自己柜子开的单，且没有任何报错。
         *
         * 本仓库对机柜早就是这个规矩（CabinetServiceImpl：「只认 locationNo，
         * siteNo/agentNo 一律反查 —— 接受它们就等于允许三者互相矛盾」）。
         */
        CabinetBrief own = notBlank(draft.cabinetNo())
                ? cabinetQuery.briefsByNos(java.util.List.of(draft.cabinetNo()))
                        .stream().findFirst().orElse(null)
                : null;
        UpdateWrapper<WoOrder> extra = new UpdateWrapper<>();
        extra.eq("wo_no", e.getWoNo());
        boolean anyExtra = false;
        if (sourceRef != null && !sourceRef.isBlank()) { extra.set("source_ref", sourceRef); anyExtra = true; }
        if (own != null && notBlank(own.agentNo())) { extra.set("agent_no", own.agentNo()); anyExtra = true; }
        if (own != null && notBlank(own.siteNo())) { extra.set("site_no", own.siteNo()); anyExtra = true; }
        if (own != null && notBlank(own.locationNo())) { extra.set("location_no", own.locationNo()); anyExtra = true; }
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
        appendDispatch(woNo, resolveAssignee(req == null ? null : req.assigneeNo(), e), WoDispatchAction.ACCEPT.name());

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
        if (!WorkOrderStatus.PROCESSING.name().equals(e.getStatus())) {
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
        String to = stateMachine.next(e.getStatus(), "DONE");

        // 2026-09-25 收紧：维修 / 装机 / 撤机必须有现场照片；故障单必须给原因分类
        String type = e.getType();
        List<String> fileNos = req == null || req.fileNos() == null ? List.of()
                : req.fileNos().stream().filter(WoOpsServiceImpl::notBlank).distinct().toList();
        boolean needPhoto = Set.of(WorkOrderType.FAULT.name(), WorkOrderType.INSTALL.name(), WorkOrderType.REMOVE.name()).contains(type);
        if (needPhoto && fileNos.isEmpty() && (req == null || !notBlank(req.photos()))) {
            throw new IllegalArgumentException("请上传至少一张现场照片");
        }
        String faultReason = req == null || !notBlank(req.faultReasonCode()) ? null
                : ai.neargo.sharehub.wo.WoFaultReason.of(req.faultReasonCode()).name();
        if (WorkOrderType.FAULT.name().equals(type) && faultReason == null) {
            throw new IllegalArgumentException("请选择故障原因");
        }
        // 撤机必须清点：清点数与系统在柜数比对，不一致挂资产差异（C8）—— 不填就没有比对的依据
        Integer counted = req == null ? null : req.countedQty();
        if (WorkOrderType.REMOVE.name().equals(type) && (counted == null || counted < 0)) {
            throw BizException.badRequest("error.wo.counted_qty_required");
        }
        String scannedLocation = req == null || !notBlank(req.locationNo()) ? null : req.locationNo().trim();
        if (!fileNos.isEmpty()) files.bind(fileNos, "WORK_ORDER", woNo, e.getAgentNo());   // 同事务：完工失败则照片仍是临时文件

        e.setStatus(to);
        woMapper.updateById(e);
        if (faultReason != null) woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("fault_reason_code", faultReason));

        WoHandle h = appendHandle(e, req, "COMPLETE");
        if (faultReason != null || !fileNos.isEmpty()) {
            handleMapper.update(null, new UpdateWrapper<WoHandle>().eq("id", h.getId())
                    .set("fault_reason_code", faultReason).set("file_nos", fileNos.isEmpty() ? null : String.join(",", fileNos)));
        }
        if (counted != null || scannedLocation != null) {
            handleMapper.update(null, new UpdateWrapper<WoHandle>().eq("id", h.getId())
                    .set("counted_qty", counted).set("location_no", scannedLocation));
        }
        recordCost(woNo, h.getId(), req);
        // 告警域据此做完工复核（关联告警已恢复 → 自动验收）；wo 不认识 alarm，只发事件
        events.publish(new ai.neargo.sharehub.api.ops.event.WorkOrderCompletedEvent(woNo, type, e.getSource(),
                e.getSourceRef(), e.getSiteNo(), e.getCabinetNo(), LocalDateTime.now().toString(), scannedLocation, counted));
        return toVO(e);
    }

    @Override
    @Transactional
    public WorkOrder derive(String inspectionWoNo, ai.neargo.sharehub.wo.ext.dto.WoExtDtos.DeriveReq req) {
        WoOrder parent = byNo(inspectionWoNo);
        if (!WorkOrderType.INSPECT.name().equals(parent.getType())) throw BizException.conflict("error.wo.derive_inspect_only", inspectionWoNo);
        if (!INSPECTING.contains(parent.getStatus())) throw BizException.conflict("error.wo.derive_not_on_site", inspectionWoNo);
        if (req == null || !notBlank(req.description())) throw BizException.badRequest("error.common.missing_parameter", "description");
        String type = WorkOrderType.of(req.type()).name();
        if (!DERIVABLE.contains(type)) throw BizException.badRequest("error.common.invalid_value", "type=" + type);
        String cabinetNo = notBlank(req.cabinetNo()) ? req.cabinetNo().trim() : parent.getCabinetNo();
        WorkOrder child = create(new WorkOrderDraft(type, "INSPECTION", inspectionWoNo + ":" + type + ":" + (cabinetNo == null ? "-" : cabinetNo),
                req.priority(), cabinetNo, null, parent.getLocationName(), null, null,
                "【巡检 " + inspectionWoNo + " 发现】" + req.description().trim(), null));
        log.info("巡检派生工单 inspection={} → {} type={} cabinetNo={}", inspectionWoNo, child.woNo(), type, cabinetNo);
        return child;
    }

    @Override
    public ai.neargo.common.core.PageResult<WorkOrder> pool(Integer page, Integer size, String type) {
        return page(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoQuery(page, size, null, WorkOrderStatus.CREATED.name(), type,
                null, null, null, null, null, null));
    }

    @Override
    @Transactional
    public WorkOrder grab(String woNo) {
        String me = ai.neargo.sharehub.auth.SecurityUtils.userNo();
        if (me == null) throw BizException.badRequest("error.common.missing_parameter", "operator");
        WoOrder e = byNo(woNo);   // 带数据范围：池外的单抢不到（与「不存在」同一结果）
        if (!WorkOrderStatus.CREATED.name().equals(e.getStatus())) throw BizException.conflict("error.wo.not_in_pool", woNo);
        // 抢单 = 派给自己 + 接单，两条边都过状态机；条件更新按 CREATED，并发两人抢只一人成功
        String dispatched = stateMachine.next(e.getStatus(), "DISPATCH");
        String accepted = stateMachine.next(dispatched, "ACCEPT");
        int n = woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).eq("status", WorkOrderStatus.CREATED.name())
                .set("status", accepted).set("assignee_name", me).set("assignee_type", "EMPLOYEE")
                .set("assignee_id", me).set("dispatch_strategy", GRAB_STRATEGY));
        if (n == 0) throw BizException.conflict("error.wo.not_in_pool", woNo);
        e.setStatus(accepted);
        e.setAssigneeName(me);
        // 策略传 GRAB：主表第 327 行已经写了 GRAB，时间轴却走默认的 MANUAL ——
        // 同一件事在两处记成两个答案，而事后追「这单是派的还是抢的」多半看的是时间轴
        appendDispatch(woNo, resolveAssignee(me, e), WoDispatchAction.DISPATCH.name(), GRAB_STRATEGY);
        appendDispatch(woNo, resolveAssignee(me, e), WoDispatchAction.ACCEPT.name(), GRAB_STRATEGY);
        markBreached(woNo, true);   // 接单即响应，和 accept 同一个度量点
        log.info("抢单 woNo={} by={}", woNo, me);
        return toVO(e);
    }

    /**
     * 工单成本（G4）：完工时的配件 + 人工金额累加到工单上。承担方：代理运维的单记到代理（代理自己的运维成本），
     * 其余记到站点（站点效益要扣掉它）。金额不能为负。
     */
    private void recordCost(String woNo, Long handleId, HandleReq req) {
        if (req == null || (req.partCost() == null && req.laborCost() == null)) return;
        java.math.BigDecimal part = req.partCost() == null ? java.math.BigDecimal.ZERO : req.partCost();
        java.math.BigDecimal labor = req.laborCost() == null ? java.math.BigDecimal.ZERO : req.laborCost();
        if (part.signum() < 0 || labor.signum() < 0) throw BizException.badRequest("error.common.invalid_value", "cost<0");
        handleMapper.update(null, new UpdateWrapper<WoHandle>().eq("id", handleId).set("part_cost", req.partCost()).set("labor_cost", req.laborCost()));
        java.util.Map<String, Object> row = woMapper.selectMaps(new QueryWrapper<WoOrder>().select("assignee_type", "assignee_name", "site_no")
                .eq("wo_no", woNo)).stream().findFirst().orElse(java.util.Map.of());
        boolean agent = "AGENT".equals(row.get("assignee_type"));
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo)
                .setSql("cost_total = COALESCE(cost_total, 0) + " + part.add(labor).toPlainString())
                .set("cost_currency", "AED")
                .set("cost_bearer_type", agent ? "AGENT" : "SITE")
                .set("cost_bearer_no", agent ? row.get("assignee_name") : row.get("site_no")));
    }

    @Override
    public List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CostRow> costSummary(java.time.LocalDate from, java.time.LocalDate to, String bearerType) {
        QueryWrapper<WoOrder> w = new QueryWrapper<WoOrder>()
                .select("cost_bearer_type AS t", "cost_bearer_no AS n", "COUNT(*) AS c", "SUM(cost_total) AS s", "MAX(cost_currency) AS cur")
                .isNotNull("cost_total").in("status", WorkOrderStatus.DONE.name(), WorkOrderStatus.AUDITED.name(), WorkOrderStatus.CLOSED.name());
        if (from != null) w.ge("created_at", from.atStartOfDay());
        if (to != null) w.lt("created_at", to.atStartOfDay());
        if (notBlank(bearerType)) w.eq("cost_bearer_type", bearerType.trim().toUpperCase());
        w.groupBy("cost_bearer_type", "cost_bearer_no").orderByDesc("SUM(cost_total)");
        return woMapper.selectMaps(w).stream().map(m -> new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.CostRow(
                (String) m.get("t"), (String) m.get("n"), ((Number) m.get("c")).longValue(),
                new java.math.BigDecimal(String.valueOf(m.get("s"))), (String) m.get("cur"))).toList();
    }

    private static final List<String> HANDOVER_FROM = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name(),
            WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name());

    @Override
    @Transactional
    public int reassignFromAgent(String agentNo, String reason) {
        List<WoOrder> open = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> woMapper.selectList(
                new QueryWrapper<WoOrder>().eq("assignee_type", "AGENT").eq("assignee_name", agentNo).in("status", HANDOVER_FROM)));
        int n = 0;
        for (WoOrder e : open) {
            String[] owner = resolveOwner(e.getSiteNo());
            // 责任人解析会跳过已停用代理，落到员工；站点没有员工责任人就按区域负载找平台运维
            String emp = owner != null && "EMPLOYEE".equals(owner[0]) ? owner[1] : regionOperator(e.getSiteNo());
            handOver(e, emp, WoDispatchAction.REASSIGN, emp == null ? "LOAD" : owner != null ? "OWNER" : "LOAD",
                    "代理 " + agentNo + " 停用" + (notBlank(reason) ? "：" + reason : ""));
            n++;
        }
        if (n > 0) log.info("代理停用改派工单 agentNo={} count={}", agentNo, n);
        return n;
    }

    @Override
    @Transactional
    public WorkOrder takeover(String woNo, String employeeNo, String reason) {
        WoOrder e = byNo(woNo);
        String from = e.getAssigneeName();
        String type = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> woMapper.selectObjs(
                new QueryWrapper<WoOrder>().select("assignee_type").eq("wo_no", woNo))).stream().findFirst().map(String::valueOf).orElse(null);
        if (!"AGENT".equals(type)) throw BizException.conflict("error.wo.takeover_agent_only", woNo);
        if (!HANDOVER_FROM.contains(e.getStatus()) || WorkOrderStatus.CREATED.name().equals(e.getStatus())) {
            throw BizException.conflict("error.wo.takeover_state", woNo);
        }
        WoSla sla = slaMapper.selectOne(new LambdaQueryWrapper<WoSla>().eq(WoSla::getWoNo, woNo).last("limit 1"));
        boolean breached = sla != null && (Integer.valueOf(1).equals(sla.getRespondBreached()) || Integer.valueOf(1).equals(sla.getResolveBreached()));
        if (!breached) throw BizException.conflict("error.wo.takeover_not_breached", woNo);
        String emp = notBlank(employeeNo) ? employeeNo.trim() : null;
        if (emp != null) {
            var b = employees.briefsOf(List.of(emp)).get(emp);
            if (b == null || !b.active()) throw BizException.badRequest("error.site.owner_not_active", emp);
        } else {
            String[] owner = resolveOwner(e.getSiteNo());
            emp = owner != null && "EMPLOYEE".equals(owner[0]) ? owner[1] : regionOperator(e.getSiteNo());
            if (emp == null) throw BizException.conflict("error.wo.takeover_no_operator", woNo);
        }
        handOver(e, emp, WoDispatchAction.TAKEOVER, notBlank(employeeNo) ? "MANUAL" : "LOAD",
                "平台接管（代理 " + from + " 超时）" + (notBlank(reason) ? "：" + reason : ""));
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("taken_over_from", from).set("taken_over_at", LocalDateTime.now()));
        if (from != null) notify.push(from, "WO_TAKEN_OVER", "工单 " + woNo + " 已超时，由平台接管");
        log.info("平台接管代理工单 woNo={} from={} to={}", woNo, from, emp);
        return toVO(byNo(woNo));
    }

    /**
     * 换人：退回待派（REJECT）再派给员工（DISPATCH），两条边都过状态机；按原状态条件更新，并发只一人赢。
     * 没有人可派（emp=null）就停在待派单池，通知主管。
     */
    private void handOver(WoOrder e, String emp, WoDispatchAction action, String strategy, String note) {
        String from = e.getStatus();
        String back = WorkOrderStatus.CREATED.name().equals(from) ? from : stateMachine.next(from, "REJECT");
        String to = emp == null ? back : stateMachine.next(back, "DISPATCH");
        int n = woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", e.getWoNo()).eq("status", from)
                .set("status", to).set("assignee_name", emp).set("assignee_id", emp).set("assignee_type", emp == null ? null : "EMPLOYEE")
                .set("dispatch_strategy", strategy));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        WoDispatch d = new WoDispatch();
        d.setWoNo(e.getWoNo());
        d.setAssigneeNo(emp);
        d.setStrategy(strategy);
        d.setAction(action.name());
        d.setDispatchedAt(LocalDateTime.now().toString());
        dispatchMapper.insert(d);
        annotate(e.getWoNo(), note);
        if (emp != null) {
            notify.push(emp, "WO_DISPATCHED", "工单 " + e.getWoNo() + "（" + e.getType() + " · " + e.getPriority() + "）改派给你：" + note);
        } else {
            for (var lead : employees.activeByRoles(OPS_LEAD_ROLES, 5)) {
                notify.push(lead.employeeNo(), "WO_UNASSIGNED", "工单 " + e.getWoNo() + " 需要重新派单：" + note);
            }
        }
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
        // 复核未通过还要放行，必须写明理由 —— 否则「系统说没修好、人说修好了」这件事查不到依据
        if (WO_REVIEW_FAILED.equals(e.getReviewStatus()) && (req == null || !notBlank(req.auditNote()))) {
            throw new IllegalArgumentException("复核未通过的工单人工验收时，请在验收说明里写明放行理由");
        }
        // 从当前态一路走到 CLOSED；起点不在 DONE/AUDITED 上的由状态机拒（含 PROCESSING —— 需先 /complete）
        for (String event : List.of("AUDIT", "CLOSE")) {
            if (WorkOrderStatus.CLOSED.name().equals(e.getStatus())) break;
            if (isBefore(e.getStatus(), event)) {
                e.setStatus(stateMachine.next(e.getStatus(), event));
            }
        }
        if (!WorkOrderStatus.CLOSED.name().equals(e.getStatus())) {
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
        /*
         * **受理人类型必须落库**（2026-09-26 补）。此前这条人工派单的路径只写了名字，
         * 而自动派单的 {@link #dispatchAs} 三列都写 —— 于是同一张表里，
         * 人工派的单 {@code assignee_type} 恒为空。两处读它的地方因此对人工派的单永远不成立：
         *   · {@link #takeover}：判不出「这是代理承接的单」⇒ **超时了也接管不了**；
         *   · {@link #recordCost}：判不出代理运维 ⇒ 成本记到站点，**代理的运维成本算不到它头上**。
         * 两者都不报错，只是结果一直是错的那一边。
         */
        String type = assigneeTypeOf(assignee);
        // 认不出类型时不写 assignee_id：那个值不是有效编号，而该列是 varchar(36)，
        // 塞一个长姓名进去会直接 Data too long（此前 wo_dispatch 那一侧就是这么 500 的）
        UpdateWrapper<WoOrder> u = new UpdateWrapper<WoOrder>().eq("wo_no", woNo)
                .set("assignee_type", type).set("dispatch_strategy", MANUAL_STRATEGY);
        if (type != null) u.set("assignee_id", assignee.trim());
        woMapper.update(null, u);
        appendDispatch(woNo, resolveAssignee(assignee, e), WoDispatchAction.DISPATCH.name(), MANUAL_STRATEGY);
        return toVO(e);
    }

    /** 派单策略留痕的取值（{@code wo_dispatch.strategy} / {@code wo_order.dispatch_strategy}）。 */
    private static final String MANUAL_STRATEGY = "MANUAL";
    /** 抢单。与 {@code wo_order.dispatch_strategy} 用同一套取值。 */
    private static final String GRAB_STRATEGY = "GRAB";

    /**
     * 受理人是代理还是员工。**认不出就留空并告警**，不猜也不拒。
     *
     * <p>为什么不拒：这个端点的历史契约是**接受姓名**（{@code wo_order.assignee_name} 是 varchar(64)，
     * 既有用例传的就是「Ahmed Field-Eng」这样的人名）。改成「必须是在册编号」会让这些派单一律 400 ——
     * 实测打红 9 条既有用例。运营端现在传的是候选人的编号，所以能认出来；老用法继续按姓名走。
     *
     * <p>留空的代价要说清楚：{@code takeover} 判不出代理单 ⇒ 超时也接管不了；
     * {@code recordCost} 判不出代理运维 ⇒ 成本记到站点。所以认不出时记 WARN，让它在日志里显形。
     */
    private String assigneeTypeOf(String no) {
        if (!notBlank(no)) return null;
        String key = no.trim();
        if (agents.briefOf(key) != null) return "AGENT";
        if (employees.briefsOf(List.of(key)).containsKey(key)) return "EMPLOYEE";
        log.warn("派单受理人既不是在册代理也不是在职员工 assignee={} —— assignee_type 留空，"
                + "该单无法被平台接管、成本也会记到站点；运营端应当从候选人列表里选（传编号）", key);
        return null;
    }

    // ——————————————————————— 列表（富行装配）———————————————————————

    @Override
    public ai.neargo.common.core.PageResult<WorkOrder> pageRich(Integer page, Integer size,
                                                                String keyword, String status, String type) {
        return page(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoQuery(page, size, keyword, status, type,
                null, null, null, null, null, null));
    }

    @Override
    public ai.neargo.common.core.PageResult<WorkOrder> page(ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoQuery q) {
        int p = (q.page() == null || q.page() < 1) ? 1 : q.page();
        int s = (q.size() == null || q.size() < 1) ? 10 : Math.min(q.size(), 200);
        LambdaQueryWrapper<WoOrder> w = new LambdaQueryWrapper<>();
        if (notBlank(q.keyword())) {
            String k = q.keyword().trim();
            w.and(x -> x.like(WoOrder::getWoNo, k).or().like(WoOrder::getCabinetNo, k).or().eq(WoOrder::getSourceRef, k));
        }
        if (notBlank(q.status())) w.in(WoOrder::getStatus, List.of(q.status().split(",")));
        if (notBlank(q.type())) w.eq(WoOrder::getType, q.type());
        if (notBlank(q.priority())) w.eq(WoOrder::getPriority, ai.neargo.sharehub.wo.WoPriority.of(q.priority()).name());
        if (notBlank(q.source())) w.eq(WoOrder::getSource, q.source());
        if (notBlank(q.siteNo())) w.eq(WoOrder::getSiteNo, q.siteNo());
        if (notBlank(q.assigneeNo())) {
            String a = q.assigneeNo().trim();
            w.and(x -> x.eq(WoOrder::getAssigneeName, a).or().apply("assignee_id = {0}", a));
        }
        if (notBlank(q.reviewStatus())) w.eq(WoOrder::getReviewStatus, q.reviewStatus());
        if (notBlank(q.slaState())) {
            LocalDateTime now = LocalDateTime.now();
            w.in(WoOrder::getStatus, OPEN_FOR_SLA).isNotNull(WoOrder::getSlaDueAt);
            switch (q.slaState().trim().toUpperCase()) {
                case "OVERDUE" -> w.apply("sla_due_at < {0}", now);
                case "DUE_SOON" -> w.apply("sla_due_at >= {0} AND sla_due_at < {1}", now, now.plusHours(2));
                default -> throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", "slaState=" + q.slaState());
            }
        }
        w.orderByAsc(WoOrder::getId);
        var r = woMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, s), w);
        return new ai.neargo.common.core.PageResult<>(enrich(r.getRecords()), r.getTotal());
    }

    /** 未完工（SLA 仍在计时）的状态。 */
    private static final List<String> OPEN_FOR_SLA = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name(),
            WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name());

    /** 富装配：wo_order 扩展列 + 派单 / 处理时间轴 + 运营维度。一次 IN 覆盖整页，不是逐行 N+1。 */
    private List<WorkOrder> enrich(List<WoOrder> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(WoOrder::getWoNo).toList();

        // wo_order 扩展列（实体外，按列名批取；DATETIME 值 toString 归一为 ISO 文本）
        java.util.Map<String, java.util.Map<String, Object>> extras = new java.util.HashMap<>();
        for (java.util.Map<String, Object> m : woMapper.selectMaps(new QueryWrapper<WoOrder>()
                .select("wo_no", "source_ref", "expected_at", "reject_reason", "reject_count",
                        "audited_by", "audited_at", "audit_result", "audit_note",
                        "assignee_type", "fault_reason_code", "close_reason", "merged_into_wo_no", "alarm_recovered_at")
                .in("wo_no", nos))) {
            extras.put(String.valueOf(m.get("wo_no")), m);
        }
        // dispatch 时间轴：按插入序扫，天然取到每单各 action 的最新一行
        java.util.Map<String, String> dispatchedAt = new java.util.HashMap<>();
        java.util.Map<String, WoDispatch> accepted = new java.util.HashMap<>();
        for (WoDispatch d : dispatchMapper.selectList(new LambdaQueryWrapper<WoDispatch>()
                .in(WoDispatch::getWoNo, nos).orderByAsc(WoDispatch::getId))) {
            if (WoDispatchAction.DISPATCH.name().equals(d.getAction())) dispatchedAt.put(d.getWoNo(), d.getDispatchedAt());
            if (WoDispatchAction.ACCEPT.name().equals(d.getAction())) accepted.put(d.getWoNo(), d);
        }
        // handle 时间轴：最新一行（现场处理）+ 最新完工行（note 前缀 COMPLETE，见 appendHandle）；系统备注（NOTE:）不算现场处理
        java.util.Map<String, WoHandle> lastHandle = new java.util.HashMap<>();
        java.util.Map<String, String> completedAt = new java.util.HashMap<>();
        for (WoHandle h : handleMapper.selectList(new LambdaQueryWrapper<WoHandle>()
                .in(WoHandle::getWoNo, nos).orderByAsc(WoHandle::getId))) {
            if (h.getNote() != null && h.getNote().startsWith("NOTE:")) continue;
            lastHandle.put(h.getWoNo(), h);
            if (h.getNote() != null && h.getNote().startsWith("COMPLETE")) {
                completedAt.put(h.getWoNo(), h.getHandledAt());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        return rows.stream().map(e -> {
            java.util.Map<String, Object> x = extras.getOrDefault(e.getWoNo(), java.util.Map.of());
            WoDispatch acc = accepted.get(e.getWoNo());
            WoHandle h = lastHandle.get(e.getWoNo());
            LocalDateTime due = parseDue(e.getSlaDueAt());
            Long remain = due == null || !OPEN_FOR_SLA.contains(e.getStatus()) ? null
                    : java.time.Duration.between(now, due).toMinutes();
            return new WorkOrder(e.getWoNo(), e.getType(), e.getSource(), e.getPriority(), e.getCabinetNo(),
                    e.getLocationName(), e.getStatus(), e.getAssigneeName(), e.getSlaDueAt(),
                    e.getDescription(), e.getWoCreatedAt(),
                    str(x.get("source_ref")), str(x.get("expected_at")),
                    iso(dispatchedAt.get(e.getWoNo())),
                    acc == null ? null : iso(acc.getDispatchedAt()),
                    h != null ? h.getAssigneeNo() : (acc == null ? null : acc.getAssigneeNo()),
                    h == null ? null : iso(h.getHandledAt()),
                    h == null ? null : h.getNote(),
                    h != null && h.getPartChanged() != null && h.getPartChanged() == 1 ? "PART_CHANGED" : null,
                    iso(completedAt.get(e.getWoNo())),
                    str(x.get("audited_by")), str(x.get("audited_at")),
                    str(x.get("audit_result")), str(x.get("audit_note")),
                    str(x.get("reject_reason")),
                    x.get("reject_count") == null ? 0 : ((Number) x.get("reject_count")).intValue(),
                    new ai.neargo.sharehub.wo.dto.WoDtos.WoOps(e.getSiteNo(), str(x.get("assignee_type")), remain,
                            e.getReviewStatus(), str(x.get("fault_reason_code")), str(x.get("close_reason")),
                            str(x.get("merged_into_wo_no")), str(x.get("alarm_recovered_at")), 0));
        }).toList();
    }

    @Override
    public ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDetail detail(String woNo) {
        WoOrder e = byNo(woNo);   // 带范围读
        WorkOrder row = enrich(List.of(e)).get(0);
        List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.TimelineItem> timeline = new java.util.ArrayList<>();
        for (WoDispatch d : dispatchMapper.selectList(new LambdaQueryWrapper<WoDispatch>().eq(WoDispatch::getWoNo, woNo))) {
            timeline.add(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.TimelineItem("DISPATCH", d.getAction(), d.getAssigneeNo(),
                    d.getStrategy(), null, List.of(), iso(d.getDispatchedAt())));
        }
        java.util.Map<Long, java.util.Map<String, Object>> handleExtras = new java.util.HashMap<>();
        for (java.util.Map<String, Object> m : handleMapper.selectMaps(new QueryWrapper<WoHandle>()
                .select("id", "fault_reason_code", "file_nos").eq("wo_no", woNo))) {
            handleExtras.put(((Number) m.get("id")).longValue(), m);
        }
        for (WoHandle h : handleMapper.selectList(new LambdaQueryWrapper<WoHandle>().eq(WoHandle::getWoNo, woNo))) {
            java.util.Map<String, Object> x = handleExtras.getOrDefault(h.getId(), java.util.Map.of());
            String fileNos = str(x.get("file_nos"));
            String note = h.getNote();
            String action = note == null ? "HANDLE" : note.startsWith("COMPLETE") ? "COMPLETE" : note.startsWith("REWORK")
                    ? "REWORK" : note.startsWith("NOTE:") ? "NOTE" : "HANDLE";
            timeline.add(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.TimelineItem("HANDLE", action, h.getAssigneeNo(), note,
                    str(x.get("fault_reason_code")), fileNos == null ? List.of() : List.of(fileNos.split(",")), iso(h.getHandledAt())));
        }
        timeline.sort(java.util.Comparator.comparing(t -> t.at() == null ? "" : t.at()));
        return new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WorkOrderDetail(row, timeline, files.listBound("WORK_ORDER", woNo));
    }

    /**
     * 按列名读回的值归一为字符串；时间列统一出 <b>ISO-8601</b>。
     *
     * <p>{@code selectMaps} 读 DATETIME 列拿到的是 {@link java.sql.Timestamp}，其 {@code toString}
     * 是空格分隔的 {@code "2026-08-05 01:04:01.4"}，而同一响应里实体字段出的是带 {@code T} 的 ISO。
     * 一个 JSON 里两种时间格式，前端只能靠 {@code new Date()} 的宽容解析兜住 —— 换个运行时就崩。
     */
    /** 时间字符串归一：DATETIME 列经 String 字段读回是空格分隔，统一补 {@code T}（见 {@link #str}）。 */
    private static String iso(String v) {
        if (v == null || v.length() < 11 || v.charAt(10) != ' ') return v;
        return v.substring(0, 10) + "T" + v.substring(11);
    }

    private static String str(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.time.LocalDateTime dt) return dt.toString();
        return String.valueOf(v);
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
                // 受理人三列一起清：只清 assignee_name 的话，类型与编号仍留在库里，
                // 于是「待派单」的单按受理人还筛得到，接管/成本也仍按那个已被退回的人算
                .set("assignee_type", null).set("assignee_id", null).set("dispatch_strategy", null)
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
    private WoHandle appendHandle(WoOrder e, HandleReq req, String notePrefix) {
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
        return h;
    }

    /** 落一行 {@code wo_dispatch}（派单/接单/驳回共用同一根「转了几手」的时间轴）。策略默认人工。 */
    private void appendDispatch(String woNo, String assigneeNo, String action) {
        appendDispatch(woNo, assigneeNo, action, MANUAL_STRATEGY);
    }

    /**
     * 同上，但由调用方指明**派单策略**。
     *
     * <p>原先这里把 {@code strategy} 写死成 {@code "MANUAL"}，于是抢单（{@link #grab}）
     * 在时间轴上也记成「人工派单」—— 留痕看着完整，说的却不是事实：
     * 事后追「这单当时是派下去的还是被抢的」，答案永远是前者。
     */
    private void appendDispatch(String woNo, String assigneeNo, String action, String strategy) {
        WoDispatch d = new WoDispatch();
        d.setWoNo(woNo);
        /*
         * `wo_dispatch.assignee_id` 是 varchar(36)，而派单入参可以是最长 64 的姓名 ——
         * 直接写会 `Data too long` ⇒ **整个派单 500**（用例撞到过）。
         * 截断而不是置空：时间轴要回答「这单从谁手里转出去的」，截断后的前 36 字符仍认得出是谁；
         * 置空则等于这一环凭空断了。真正的编号都远短于 36，走不到这一步。
         */
        d.setAssigneeNo(assigneeNo != null && assigneeNo.length() > 36 ? assigneeNo.substring(0, 36) : assigneeNo);
        d.setStrategy(strategy);
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
        if (woNo == null || woNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "woNo");
        WoOrder e = woMapper.selectOne(new LambdaQueryWrapper<WoOrder>()
                .eq(WoOrder::getWoNo, woNo).last("limit 1"));
        if (e == null) throw BizException.notFound(woNo);
        return e;
    }

    @Override
    @Transactional
    public int sweepSlaBreaches() {
        LocalDateTime now = LocalDateTime.now();
        // 响应：过了 due 还停在「尚未接单」的两个态；解决：过了 due 还没到 DONE
        int n = markOverdue(now, true, RESPOND_PENDING) + markOverdue(now, false, RESOLVE_PENDING);
        escalate(now, true);
        escalate(now, false);
        return n;
    }

    private static final List<String> RESPOND_PENDING = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name());
    private static final List<String> RESOLVE_PENDING = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name(),
            WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name());

    /**
     * SLA 超时升级通知（对齐清单 E2）：响应超时 → 规则的 escalate_to（未配置取 {@code wo.escalate.respond_to}，默认运维）；
     * 解决超时 → resolve_escalate_to（默认 {@code wo.escalate.resolve_to} = 管理员）。
     * 角色码按工单站点所在区域找在职员工，区域里没有就退到全体该角色。<b>每档只通知一次</b>（先占 *_escalated_at 再发）。
     * 已经不在待处理态的（超时后才被接 / 被完成）只占位不通知 —— 事情已经在动了，通知只会是噪音。
     */
    private int escalate(LocalDateTime now, boolean respond) {
        String flagCol = respond ? "respond_breached" : "resolve_breached";
        String atCol = respond ? "respond_escalated_at" : "resolve_escalated_at";
        // 新的优先：积压的历史超时单不该挡住刚超时、还来得及救的那张
        List<WoSla> due = slaMapper.selectList(new QueryWrapper<WoSla>().eq(flagCol, 1).isNull(atCol).orderByDesc("id").last("limit 200"));
        // 积压静默：超时已超过一天的历史单只占位不通知 —— 上线第一轮若把积压全推一遍，会一次轰炸几千条，真该看的那条被淹掉
        String dueCol = respond ? "respond_due_at" : "resolve_due_at";
        slaMapper.update(null, new UpdateWrapper<WoSla>().eq(flagCol, 1).isNull(atCol).apply(dueCol + " < {0}", now.minusDays(1))
                .set(atCol, now));
        int sent = 0;
        for (WoSla sla : due) {
            if (slaMapper.update(null, new UpdateWrapper<WoSla>().eq("id", sla.getId()).isNull(atCol).set(atCol, now)) == 0) continue;
            WoOrder wo = woMapper.selectOne(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getWoNo, sla.getWoNo()).last("limit 1"));
            if (wo == null || !(respond ? RESPOND_PENDING : RESOLVE_PENDING).contains(wo.getStatus())) continue;
            WoSlaRule rule = activeRule(wo.getType(), wo.getPriority());
            String spec = rule == null ? null : respond ? rule.getEscalateTo() : rule.getResolveEscalateTo();
            if (!notBlank(spec)) spec = params.textOf(respond ? "wo.escalate.respond_to" : "wo.escalate.resolve_to", respond ? "OPS" : "ADMIN");
            java.util.Set<String> to = escalationRecipients(spec, wo.getSiteNo());
            String what = respond ? "响应超时（仍未接单）" : "解决超时（仍未完工）";
            for (String r : to) {
                notify.push(r, respond ? "WO_SLA_RESPOND_BREACH" : "WO_SLA_RESOLVE_BREACH",
                        "工单 " + wo.getWoNo() + "（" + wo.getType() + " / " + wo.getPriority() + "）" + what
                                + (wo.getSiteNo() == null ? "" : "，站点 " + wo.getSiteNo()) + (wo.getAssigneeName() == null ? "，尚未派人" : "，处理人 " + wo.getAssigneeName()));
            }
            if (to.isEmpty()) log.warn("工单超时升级找不到通知对象 woNo={} spec={}，请在 SLA 规则里配置升级对象", wo.getWoNo(), spec);
            else sent++;
        }
        return sent;
    }

    /** 升级对象：逗号分隔，员工号直接用；其余当角色码 —— 先找覆盖站点区域的在职员工，没有再退到该角色全体。 */
    private java.util.Set<String> escalationRecipients(String spec, String siteNo) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        String region = siteNo == null ? null : siteQuery.briefsByNos(List.of(siteNo)).stream().findFirst()
                .map(ai.neargo.sharehub.api.platform.dto.SiteBrief::regionId).orElse(null);
        for (String raw : spec.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            var emp = employees.briefsOf(List.of(t)).get(t);
            if (emp != null) {
                if (emp.active()) out.add(emp.employeeNo());
                continue;
            }
            List<ai.neargo.sharehub.api.platform.dto.EmployeeBrief> hit = region == null ? List.of()
                    : employees.activeInRegion(java.util.Set.of(t), region, 20);
            if (hit.isEmpty()) hit = employees.activeByRoles(java.util.Set.of(t), 20);
            hit.forEach(e -> out.add(e.employeeNo()));
        }
        return out;
    }

    /**
     * 把「due 已过、标记还是 0、且工单仍停在 {@code pendingStatuses} 里」的那些标成超时。
     *
     * <p>时间比较**下推到 SQL**：`respond_due_at` 是 DATETIME 列而实体按 String 映射
     * （见 {@link #parseDue} 的说明），在 Java 侧比就得把整表捞回来再逐行 parse。
     * 绑 {@link LocalDateTime} 参数由驱动按时间戳比较，两种字面量格式都不受影响。
     */
    private int markOverdue(LocalDateTime now, boolean respond, List<String> pendingStatuses) {
        String dueCol = respond ? "respond_due_at" : "resolve_due_at";
        String flagCol = respond ? "respond_breached" : "resolve_breached";
        List<WoSla> due = slaMapper.selectList(new QueryWrapper<WoSla>()
                .eq(flagCol, 0)
                .isNotNull(dueCol)
                .apply(dueCol + " < {0}", now));
        if (due.isEmpty()) return 0;

        // 一次取回这批单的状态，别逐行查库（超时积压时这批可能不小）
        List<String> woNos = due.stream().map(WoSla::getWoNo).toList();
        java.util.Map<String, String> statusByNo = woMapper.selectList(new LambdaQueryWrapper<WoOrder>()
                        .in(WoOrder::getWoNo, woNos))
                .stream().collect(java.util.stream.Collectors.toMap(WoOrder::getWoNo, WoOrder::getStatus,
                        (a, b) -> a));

        int n = 0;
        for (WoSla sla : due) {
            String status = statusByNo.get(sla.getWoNo());
            // 查不到工单：数据不一致，**跳过而不是当成超时** —— 标错比不标更难查
            if (status == null || !pendingStatuses.contains(status)) continue;
            if (respond) sla.setRespondBreached(1); else sla.setResolveBreached(1);
            slaMapper.updateById(sla);
            n++;
        }
        if (n > 0) {
            log.warn("SLA 超时扫描标记 {} 单 kind={} —— 这些单已过期限且仍未{}，需要派单/催办",
                    n, respond ? "respond" : "resolve", respond ? "接单" : "完工");
        }
        return n;
    }

    /** 取该工单类型当前启用的 SLA 规则；没有配置则不计时（due 为空，永不超时）。 */
    /** SLA 规则：先找「类型 × 优先级」专属档，无则类型默认（priority='*'），再无则不设 SLA。 */
    private WoSlaRule activeRule(String woType, String priority) {
        List<WoSlaRule> rows = slaRuleMapper.selectList(new LambdaQueryWrapper<WoSlaRule>()
                .eq(WoSlaRule::getWoType, woType)
                .eq(WoSlaRule::getActive, 1)
                .orderByDesc(WoSlaRule::getId));
        return rows.stream().filter(r -> priority != null && priority.equals(r.getPriority())).findFirst()
                .orElse(rows.stream().filter(r -> r.getPriority() == null || "*".equals(r.getPriority())).findFirst().orElse(null));
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
        boolean breached = LocalDateTime.now().isAfter(parseDue(due));
        if (!breached) return;
        if (respond) sla.setRespondBreached(1); else sla.setResolveBreached(1);
        slaMapper.updateById(sla);
    }

    /**
     * SLA 到期时刻解析：两种格式都认。
     *
     * <p>{@code wo_sla.respond_due_at/resolve_due_at} 是 <b>DATETIME 列而实体按 String 存取</b>——
     * 写进去是 {@code LocalDateTime.toString()}（带 {@code T}），读回来是驱动给的
     * {@code "2026-08-05 02:12:08.302"}（空格）。只认 ISO 的话每次接单/关单都
     * {@code DateTimeParseException → 500}。这条此前没暴露，仅仅因为 {@code wo_sla_rule} 一直是空表
     * （无规则 → due 为 null → 不解析）；一灌 SLA 规则种子，工单流转全线 500。
     */
    private static LocalDateTime parseDue(String due) {
        if (due == null || due.isBlank()) return null;   // 没配 SLA 的单：列表富化逐行调，null 在这里就得接住
        String s = due.trim();
        if (s.length() > 10 && s.charAt(10) == ' ') s = s.substring(0, 10) + "T" + s.substring(11);
        return LocalDateTime.parse(s);
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

    // ——————————————————————— 承接业务告警（2026-09-25，TDD-运营核心流程/06）———————————————————————

    @Override
    @Transactional
    public String openOrAttach(ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft d) {
        if (d == null || !notBlank(d.mergeKey())) throw new IllegalArgumentException("合并键必填");
        ai.neargo.sharehub.wo.WoPriority p = ai.neargo.sharehub.wo.WoPriority.of(d.priority());
        String type = WorkOrderType.of(d.type()).name();
        List<WoOrder> same = woMapper.selectList(new LambdaQueryWrapper<WoOrder>()
                .and(w -> w.eq(WoOrder::getSourceRef, d.mergeKey()).or().likeRight(WoOrder::getSourceRef, d.mergeKey() + "#"))
                .orderByDesc(WoOrder::getId));
        WoOrder live = same.stream().filter(o -> !WorkOrderStatus.CLOSED.name().equals(o.getStatus())).findFirst().orElse(null);
        if (live != null) {
            // 未完结（含 DONE 待验收：复核会把新挂上的告警一并看）→ 挂靠
            if (p.higherThan(ai.neargo.sharehub.wo.WoPriority.of(live.getPriority()))) {
                raisePriority(live.getWoNo(), p.name(), "新增关联告警 " + d.alarmNo());
            }
            annotate(live.getWoNo(), "新增关联告警 " + d.alarmNo());
            return live.getWoNo();
        }
        // 完结后复发 = 新问题：新键新单（键带序号，唯一约束仍然成立）
        String key = same.isEmpty() ? d.mergeKey() : d.mergeKey() + "#" + (same.size() + 1);
        String woNo;
        try {
            woNo = create(new WorkOrderDraft(type, "ALERT", key, p.name(), d.cabinetNo(), null, null, null, null,
                    d.description(), null)).woNo();
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // 并发下另一方刚用同一合并键开了单：以它为准
            WoOrder won = woMapper.selectOne(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getSourceRef, key).last("limit 1"));
            if (won == null) throw race;
            return won.getWoNo();
        }
        // 站点级告警没有机柜，归属三列反查不到 —— 按站点补写（站点归属本身就是数据范围锚点）
        if (!notBlank(d.cabinetNo()) && notBlank(d.siteNo())) {
            ai.neargo.sharehub.api.platform.dto.SiteBrief site = siteBrief(d.siteNo());
            woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("site_no", d.siteNo())
                    .set(site != null && notBlank(site.agentNo()), "agent_no", site == null ? null : site.agentNo()));
        }
        String[] owner = resolveOwner(d.siteNo());
        String regional = owner == null ? regionOperator(d.siteNo()) : null;
        if (owner != null) {
            dispatchAs(woNo, owner[0], owner[1], "OWNER");
        } else if (regional != null) {
            // 无责任人 / 责任代理暂停 → 平台区域运维中在手未完结工单最少的一位（执行清单 A3）
            dispatchAs(woNo, "EMPLOYEE", regional, "LOAD");
        } else {
            log.warn("告警工单无可派责任人 woNo={} siteNo={} —— 需运维主管手工派单，并为该站点补运维责任人", woNo, d.siteNo());
            for (var lead : employees.activeByRoles(OPS_LEAD_ROLES, 5)) {
                notify.push(lead.employeeNo(), "WO_UNASSIGNED", "工单 " + woNo + " 无可派责任人（站点 " + d.siteNo() + "），请手工派单");
            }
        }
        return woNo;
    }

    /** 站点运维责任人：生效的 OPERATE 代理（未暂停）优先，否则平台员工责任人（在职）。返回 [type, no] 或 null。 */
    private String[] resolveOwner(String siteNo) {
        ai.neargo.sharehub.api.platform.dto.SiteBrief s = siteBrief(siteNo);
        if (s == null) return null;
        if (notBlank(s.operateAgentNo())) {
            ai.neargo.sharehub.api.platform.dto.AgentBrief a = agents.briefOf(s.operateAgentNo());
            if (a != null && a.enabled()) return new String[]{"AGENT", s.operateAgentNo()};
        }
        if (notBlank(s.opsEmployeeNo())) {
            var emp = employees.briefsOf(List.of(s.opsEmployeeNo())).get(s.opsEmployeeNo());
            if (emp != null && emp.active()) return new String[]{"EMPLOYEE", s.opsEmployeeNo()};
        }
        return null;
    }

    /** 区域运维：OPS 角色、数据范围覆盖站点所在区域的在职员工中，在手未完结工单最少者；没有返回 null。 */
    String regionOperator(String siteNo) {
        ai.neargo.sharehub.api.platform.dto.SiteBrief s = siteBrief(siteNo);
        if (s == null) return null;
        List<ai.neargo.sharehub.api.platform.dto.EmployeeBrief> ops = employees.activeInRegion(OPS_LEAD_ROLES, s.regionId(), 50);
        if (ops.isEmpty()) return null;
        List<String> nos = ops.stream().map(ai.neargo.sharehub.api.platform.dto.EmployeeBrief::employeeNo).toList();
        java.util.Map<String, Long> load = new java.util.HashMap<>();
        for (java.util.Map<String, Object> m : ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                woMapper.selectMaps(new QueryWrapper<WoOrder>().select("assignee_name AS a", "COUNT(*) AS n")
                        .in("assignee_name", nos).in("status", OPEN_FOR_SLA).groupBy("assignee_name")))) {
            load.put(String.valueOf(m.get("a")), ((Number) m.get("n")).longValue());
        }
        return nos.stream().min(java.util.Comparator.comparingLong((String n) -> load.getOrDefault(n, 0L)).thenComparing(n -> n))
                .orElse(null);
    }

    private ai.neargo.sharehub.api.platform.dto.SiteBrief siteBrief(String siteNo) {
        if (!notBlank(siteNo)) return null;
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> siteQuery.briefsByNos(List.of(siteNo)))
                .stream().findFirst().orElse(null);
    }

    private void dispatchAs(String woNo, String assigneeType, String assigneeNo, String strategy) {
        WoOrder e = byNo(woNo);
        e.setStatus(stateMachine.next(e.getStatus(), "DISPATCH"));
        e.setAssigneeName(assigneeNo);
        woMapper.updateById(e);
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("assignee_type", assigneeType)
                .set("assignee_id", assigneeNo).set("dispatch_strategy", strategy));
        WoDispatch d = new WoDispatch();
        d.setWoNo(woNo);
        d.setAssigneeNo(assigneeNo);
        d.setStrategy(strategy);
        d.setAction(WoDispatchAction.DISPATCH.name());
        d.setDispatchedAt(LocalDateTime.now().toString());
        dispatchMapper.insert(d);
        notify.push(assigneeNo, "WO_DISPATCHED", "新工单 " + woNo + "（" + e.getType() + " · " + e.getPriority() + "）");
    }

    @Override
    @Transactional
    public void raisePriority(String woNo, String priority, String reason) {
        WoOrder e = byNo(woNo);
        ai.neargo.sharehub.wo.WoPriority p = ai.neargo.sharehub.wo.WoPriority.of(priority);
        if (!p.higherThan(ai.neargo.sharehub.wo.WoPriority.of(e.getPriority()))) return;
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("priority", p.name()));
        // SLA 只提前不推后：按新档位重算，比原截止时刻早才生效
        WoSlaRule rule = activeRule(e.getType(), p.name());
        WoSla sla = slaMapper.selectOne(new LambdaQueryWrapper<WoSla>().eq(WoSla::getWoNo, woNo).last("limit 1"));
        if (rule != null && sla != null) {
            LocalDateTime created = parseDue(e.getWoCreatedAt());
            LocalDateTime base = created == null ? LocalDateTime.now() : created;
            LocalDateTime respond = rule.getResponseMins() == null || rule.getResponseMins() <= 0 ? null : base.plusMinutes(rule.getResponseMins());
            LocalDateTime resolve = rule.getResolveMins() == null || rule.getResolveMins() <= 0 ? null : base.plusMinutes(rule.getResolveMins());
            LocalDateTime oldRespond = parseDue(sla.getRespondDueAt()), oldResolve = parseDue(sla.getResolveDueAt());
            boolean changed = false;
            if (respond != null && (oldRespond == null || respond.isBefore(oldRespond))) { sla.setRespondDueAt(respond.toString()); changed = true; }
            if (resolve != null && (oldResolve == null || resolve.isBefore(oldResolve))) {
                sla.setResolveDueAt(resolve.toString());
                woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("sla_due_at", resolve.toString()));
                changed = true;
            }
            if (changed) slaMapper.updateById(sla);
        }
        appendDispatch(woNo, SYSTEM, "PRIORITY_UP");
        annotate(woNo, "优先级 " + e.getPriority() + " → " + p.name() + (notBlank(reason) ? "：" + reason : ""));
        log.info("工单升优先级 woNo={} {}→{}", woNo, e.getPriority(), p);
    }

    @Override
    @Transactional
    public void withdraw(String woNo, String reason) {
        WoOrder e = byNo(woNo);
        String before = e.getStatus();
        e.setStatus(stateMachine.next(before, "WITHDRAW"));   // 只接受 CREATED / DISPATCHED
        woMapper.updateById(e);
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("close_reason", "WITHDRAWN")
                .set("closed_at", LocalDateTime.now()));
        appendDispatch(woNo, SYSTEM, "WITHDRAW");
        annotate(woNo, "撤单：" + reason);
        if (WorkOrderStatus.DISPATCHED.name().equals(before) && notBlank(e.getAssigneeName())) {
            notify.push(e.getAssigneeName(), "WO_WITHDRAWN", "工单 " + woNo + " 已撤销：" + reason + "，无需前往");
        }
        log.info("工单撤销 woNo={} from={} reason={}", woNo, before, reason);
    }

    @Override
    @Transactional
    public void annotate(String woNo, String note) {
        WoHandle h = new WoHandle();
        h.setWoNo(woNo);
        h.setAssigneeNo(SYSTEM);
        h.setNote("NOTE: " + note);
        h.setPartChanged(0);
        h.setDeviceChanged(0);
        h.setHandledAt(LocalDateTime.now().toString());
        handleMapper.insert(h);
    }

    @Override
    @Transactional
    public void markAlarmRecovered(String woNo, LocalDateTime at) {
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).isNull("alarm_recovered_at")
                .set("alarm_recovered_at", at == null ? LocalDateTime.now() : at));
        annotate(woNo, "关联告警已恢复（已接单，不撤单；到场后请确认现场）");
    }

    @Override
    @Transactional
    public void mergeInto(String fromWoNo, String toWoNo, String reason) {
        if (fromWoNo.equals(toWoNo)) return;
        WoOrder from = byNo(fromWoNo);
        WoOrder to = byNo(toWoNo);
        from.setStatus(stateMachine.next(from.getStatus(), "MERGE"));
        woMapper.updateById(from);
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", fromWoNo).set("close_reason", "DUPLICATE")
                .set("merged_into_wo_no", toWoNo).set("closed_at", LocalDateTime.now()));
        appendDispatch(fromWoNo, SYSTEM, "MERGE");
        annotate(toWoNo, "并入工单 " + fromWoNo + (notBlank(reason) ? "：" + reason : ""));
        raisePriority(toWoNo, from.getPriority(), "并入 " + fromWoNo);
    }

    @Override
    @Transactional
    public void recordReview(String woNo, boolean passed,
                             List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.ReviewItem> items) {
        WoOrder e = byNo(woNo);
        if (e.getReviewStatus() != null) return;   // 已复核（事件重投）：幂等跳过
        String detail = items == null ? "[]" : items.stream()
                .map(i -> String.format("{\"alarmNo\":\"%s\",\"code\":\"%s\",\"passed\":%s,\"note\":\"%s\"}",
                        i.alarmNo(), i.code(), i.passed(), i.note() == null ? "" : i.note().replace("\"", "'")))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String status = passed ? ai.neargo.sharehub.wo.WoReviewStatus.PASSED.name() : WO_REVIEW_FAILED;
        woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("review_status", status)
                .set("review_detail", detail.length() > 2000 ? detail.substring(0, 2000) : detail).set("reviewed_at", LocalDateTime.now()));
        appendDispatch(woNo, SYSTEM, "REVIEW");
        if (passed && WorkOrderStatus.DONE.name().equals(e.getStatus())) {
            e.setStatus(stateMachine.next(stateMachine.next(e.getStatus(), "AUDIT"), "CLOSE"));
            woMapper.updateById(e);
            woMapper.update(null, new UpdateWrapper<WoOrder>().eq("wo_no", woNo).set("close_reason", "RESOLVED")
                    .set("audit_result", "PASS").set("audit_note", "复核通过，自动验收").set("audited_by", SYSTEM)
                    .set("audited_at", LocalDateTime.now()).set("closed_at", LocalDateTime.now()));
            markBreached(woNo, false);
            log.info("工单复核通过自动验收 woNo={}", woNo);
        } else if (!passed) {
            log.info("工单复核未通过 woNo={}，等待人工返工或放行", woNo);
        }
    }

    @Override
    public List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate> candidates(String siteNo) {
        List<ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate> out = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        String[] owner = resolveOwner(siteNo);
        ai.neargo.sharehub.api.platform.dto.SiteBrief s = siteBrief(siteNo);
        if (owner != null) {
            String name = "AGENT".equals(owner[0])
                    ? java.util.Optional.ofNullable(agents.briefOf(owner[1])).map(ai.neargo.sharehub.api.platform.dto.AgentBrief::name).orElse(owner[1])
                    : java.util.Optional.ofNullable(employees.briefsOf(List.of(owner[1])).get(owner[1])).map(ai.neargo.sharehub.api.platform.dto.EmployeeBrief::name).orElse(owner[1]);
            out.add(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate(owner[0], owner[1], name, true));
            seen.add(owner[1]);
        }
        if (s != null && notBlank(s.operateAgentNo()) && seen.add(s.operateAgentNo())) {
            var a = agents.briefOf(s.operateAgentNo());
            if (a != null && a.enabled()) out.add(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate("AGENT", a.agentNo(), a.name(), false));
        }
        for (var emp : employees.activeByRoles(OPS_LEAD_ROLES, 100)) {
            if (seen.add(emp.employeeNo())) {
                out.add(new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AssigneeCandidate("EMPLOYEE", emp.employeeNo(), emp.name(), false));
            }
        }
        return out;
    }

    @Override
    public ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoSummary summary() {
        long toDispatch = woMapper.selectCount(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getStatus, WorkOrderStatus.CREATED.name()));
        List<String> open = List.of(WorkOrderStatus.CREATED.name(), WorkOrderStatus.DISPATCHED.name(),
                WorkOrderStatus.ACCEPTED.name(), WorkOrderStatus.PROCESSING.name());
        LocalDateTime now = LocalDateTime.now();
        long dueSoon = 0, overdue = 0;
        for (WoOrder o : woMapper.selectList(new LambdaQueryWrapper<WoOrder>().select(WoOrder::getSlaDueAt)
                .in(WoOrder::getStatus, open).isNotNull(WoOrder::getSlaDueAt))) {
            LocalDateTime due = parseDue(o.getSlaDueAt());
            if (due == null) continue;
            if (due.isBefore(now)) overdue++;
            else if (due.isBefore(now.plusHours(2))) dueSoon++;
        }
        long reviewFailed = woMapper.selectCount(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getStatus, WorkOrderStatus.DONE.name())
                .eq(WoOrder::getReviewStatus, WO_REVIEW_FAILED));
        return new ai.neargo.sharehub.wo.ext.dto.WoExtDtos.WoSummary(toDispatch, dueSoon, overdue, reviewFailed);
    }

    @Override
    public String statusOf(String woNo) {
        WoOrder e = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> woMapper.selectOne(
                new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getWoNo, woNo).last("limit 1")));
        return e == null ? null : e.getStatus();
    }

    @Override
    public java.util.Map<String, String> closeReasons(java.util.Collection<String> woNos) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (woNos == null || woNos.isEmpty()) return out;
        for (java.util.Map<String, Object> m : ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                woMapper.selectMaps(new QueryWrapper<WoOrder>().select("wo_no", "close_reason").in("wo_no", woNos)
                        .isNotNull("close_reason")))) {
            out.put(String.valueOf(m.get("wo_no")), String.valueOf(m.get("close_reason")));
        }
        return out;
    }
}
