package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.auth.StaffContext;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.inv.dto.IntakeDtos;
import ai.neargo.sharehub.inv.dto.MaterialDtos.MaterialDocReq;
import ai.neargo.sharehub.inv.dto.OperationDtos.Operation;
import ai.neargo.sharehub.inv.dto.OperationDtos.Step;
import ai.neargo.sharehub.inv.port.InventoryPort;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.IntakeService;
import ai.neargo.sharehub.inv.service.InventoryOperationService;
import ai.neargo.sharehub.inv.service.MaterialService;
import ai.neargo.sharehub.inv.service.TransferOpsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 库存执行异常（对接明细 F04 / F05）。查回的依据：库存服务的命令记录与业务写入同一事务提交
 * （ai-shop《进销存独立服务-ShareHub对接方案》§4.1），所以过了在途窗口还查不到 = 没执行；保留期 ≥ 90 天。
 */
@Service
public class InventoryOperationServiceImpl implements InventoryOperationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryOperationServiceImpl.class);
    /** 在途窗口：步骤发出后这么久内查不到，可能只是还在路上，不下结论。 */
    static final Duration IN_FLIGHT = Duration.ofMinutes(2);
    private static final Set<String> UNFINISHED = Set.of("PREPARED", "NEEDS_REVIEW");
    /** 库存服务幂等键上限（HttpInventoryApi 发送时同样截断）。 */
    private static final int KEY_MAX = 64;

    private final JdbcTemplate jdbc;
    private final RemoteInventoryPort remote;
    private final MaterialService materials;
    private final IntakeService intakes;
    private final InventoryPort inventory;
    private final TransferOpsService transfers;

    public InventoryOperationServiceImpl(JdbcTemplate jdbc, RemoteInventoryPort remote, MaterialService materials, IntakeService intakes,
                                         InventoryPort inventory, TransferOpsService transfers) {
        this.jdbc = jdbc;
        this.remote = remote;
        this.materials = materials;
        this.intakes = intakes;
        this.inventory = inventory;
        this.transfers = transfers;
    }

    private record Row(String operationNo, String kind, String requestKey, String bizRef, String phase, int attempts, String lastError,
                       String payload, String createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    @Override
    public PageResult<Operation> page(String phase, String kind, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        StringBuilder where = new StringBuilder(" WHERE tenant_id=?");
        List<Object> args = new ArrayList<>(List.of(SecurityUtils.tenantId()));
        if (phase == null || phase.isBlank()) where.append(" AND phase IN ('PREPARED','NEEDS_REVIEW')");
        else {
            where.append(" AND phase=?");
            args.add(phase.trim());
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND kind=?");
            args.add(kind.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Operation> list = rows("SELECT operation_no,kind,request_key,biz_ref,phase,attempts,last_error,payload,created_by,created_at,updated_at"
                + " FROM inv_operation" + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?", pageArgs.toArray()).stream().map(this::view).toList();
        return new PageResult<>(list, total == null ? 0 : total);
    }

    @Override
    public Operation resolve(String operationNo) {
        Row op = require(operationNo);
        if (!UNFINISHED.contains(op.phase())) return view(op);
        InventoryApi api = remote.api();
        LocalDateTime settled = LocalDateTime.now().minus(IN_FLIGHT);
        for (Step st : steps(op.operationNo())) {
            if (!"UNKNOWN".equals(st.status()) || st.updatedAt() == null || st.updatedAt().isAfter(settled)) continue;
            String key = stepKey(op.operationNo(), st.code());
            try {
                Optional<String> ref = isClaim(st.code())
                        ? api.claimByRequest(key).map(InventoryApi.Claim::claimNo)
                        : api.command(key).map(c -> refOf(c.resultBody()));
                if (ref.isPresent()) {
                    jdbc.update("UPDATE inv_operation_step SET status='COMPLETED',remote_ref=?,last_error=NULL WHERE operation_no=? AND step_code=?",
                            ref.get(), op.operationNo(), st.code());
                } else {
                    jdbc.update("UPDATE inv_operation_step SET status='FAILED',last_error=? WHERE operation_no=? AND step_code=?",
                            "Not found in inventory service after in-flight window; not executed", op.operationNo(), st.code());
                }
            } catch (InventoryRemoteException e) {
                // 库存服务此刻查不了：保持结果不明，下一轮再查
                log.warn("库存执行异常查回失败 operationNo={} step={} code={}，下一轮重试", op.operationNo(), st.code(), e.code);
            }
        }
        settle(op.operationNo());
        return view(require(operationNo));
    }

    /** 按步骤状态定操作阶段：没有结果不明、也没有远端已执行的 = 没东西可恢复，记 REJECTED。 */
    private void settle(String operationNo) {
        int unknown = count(operationNo, "UNKNOWN");
        int done = count(operationNo, "COMPLETED");
        if (unknown == 0 && done == 0) {
            jdbc.update("UPDATE inv_operation SET phase='REJECTED',last_error=? WHERE operation_no=? AND phase IN ('PREPARED','NEEDS_REVIEW')",
                    "Inventory service did not execute any step; nothing to recover", operationNo);
        } else if (unknown == 0) {
            jdbc.update("UPDATE inv_operation SET phase='NEEDS_REVIEW',last_error=? WHERE operation_no=? AND phase IN ('PREPARED','NEEDS_REVIEW')",
                    "Inventory service executed " + done + " step(s); retry the original request to finish locally", operationNo);
        }
    }

    @Override
    public int resolveAll() {
        List<String> nos = jdbc.queryForList("SELECT DISTINCT o.operation_no FROM inv_operation o JOIN inv_operation_step s ON s.operation_no=o.operation_no"
                + " WHERE o.tenant_id=? AND o.phase IN ('PREPARED','NEEDS_REVIEW') AND s.status='UNKNOWN' AND s.updated_at < ? LIMIT 200",
                String.class, SecurityUtils.tenantId(), Timestamp.valueOf(LocalDateTime.now().minus(IN_FLIGHT)));
        for (String no : nos) resolve(no);
        return nos.size();
    }

    @Override
    public Operation retry(String operationNo) {
        Row op = require(operationNo);
        if (!UNFINISHED.contains(op.phase())) throw BizException.conflict("error.common.state_changed");
        if (!retryable(op)) throw new AccessDeniedException("inventory operation retry");
        JsonNode in = Json.read(op.payload(), JsonNode.class);
        String key = op.requestKey();
        switch (op.kind()) {
            case "MATERIAL_DOC" -> materials.createDoc(Json.read(op.payload(), MaterialDocReq.class));
            case "INTAKE_INBOUND" -> intakes.inbound(text(in, "intakeNo"),
                    new IntakeDtos.ItemsReq(List.of(new IntakeDtos.ItemRef(text(in, "itemType"), text(in, "itemNo")))));
            // 出库池四个动作按原 requestKey 重走端口（出库时的物流登记在控制器层，重试不重复记）
            case "DEVICE_ISSUE" -> inventory.dispatch(text(in, "agentNo"), text(in, "siteNo"), text(in, "receiverNo"), list(in, "cabinetNos"), key);
            case "DEVICE_CLAIM" -> inventory.claim(list(in, "cabinetNos"), key);
            case "DEVICE_REDIRECT" -> inventory.redirect(list(in, "cabinetNos"), text(in, "agentNo"), text(in, "siteNo"), text(in, "receiverNo"),
                    text(in, "reason"), key);
            case "DEVICE_REVOKE" -> inventory.returnToWarehouse(list(in, "cabinetNos"), null, text(in, "reason"), key);
            case "TRANSFER_SHIP" -> transfers.ship(text(in, "transferNo"));
            case "TRANSFER_RECEIVE" -> transfers.receive(text(in, "transferNo"), list(in, "itemNos"), "执行异常重试");
            default -> throw BizException.conflict("error.inventory.operation_not_retryable", op.kind());
        }
        log.info("库存执行异常原样重试 operationNo={} kind={}", operationNo, op.kind());
        return view(require(operationNo));
    }

    // —— 内部 ——

    /** 只有原操作人、且仍持有原动作权限的才能重试（重试 = 以原人身份再走一遍原动作）。 */
    private boolean retryable(Row op) {
        if (!UNFINISHED.contains(op.phase()) || op.createdBy() == null || !op.createdBy().equals(SecurityUtils.userNo())) return false;
        String perm = switch (op.kind()) {
            case "MATERIAL_DOC" -> isInboundPurpose(Json.read(op.payload(), JsonNode.class)) ? "device:inventory:inbound" : "device:inventory:transfer";
            case "INTAKE_INBOUND" -> "device:inventory:inbound";
            case "DEVICE_ISSUE", "DEVICE_CLAIM", "DEVICE_REDIRECT", "DEVICE_REVOKE", "TRANSFER_SHIP", "TRANSFER_RECEIVE" -> "device:inventory:transfer";
            default -> null;
        };
        return perm != null && StaffContext.hasPerm(perm);
    }

    private static boolean isInboundPurpose(JsonNode in) {
        String p = text(in, "purpose");
        return "PURCHASE".equals(p) || "OPENING".equals(p) || "RETURN".equals(p);
    }

    private static boolean isClaim(String code) {
        return code.equals("CLAIM") || code.startsWith("CLAIM_") || code.startsWith("RECLAIM_");
    }

    private static String stepKey(String operationNo, String code) {
        String k = operationNo + "-" + code;
        return k.length() > KEY_MAX ? k.substring(0, KEY_MAX) : k;
    }

    /** 命令结果里认得的单号（入库 / 出库单号、领取号），认不得就存原文（截断）。 */
    private static String refOf(String resultBody) {
        JsonNode n = resultBody == null ? null : Json.read(resultBody, JsonNode.class, null);
        if (n != null) for (String f : List.of("docNo", "claimNo", "inboundNo", "outboundNo", "locationId", "holdId")) {
            if (n.hasNonNull(f)) return n.get(f).asText();
        }
        String raw = resultBody == null ? "" : resultBody;
        return raw.length() > 128 ? raw.substring(0, 128) : raw;
    }

    private static String text(JsonNode n, String f) {
        return n == null || !n.hasNonNull(f) ? null : n.get(f).asText();
    }

    private static List<String> list(JsonNode n, String f) {
        List<String> out = new ArrayList<>();
        if (n != null && n.get(f) != null) n.get(f).forEach(x -> out.add(x.asText()));
        return out;
    }

    private int count(String operationNo, String status) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inv_operation_step WHERE operation_no=? AND status=?", Integer.class, operationNo, status);
        return n == null ? 0 : n;
    }

    private Row require(String operationNo) {
        List<Row> found = rows("SELECT operation_no,kind,request_key,biz_ref,phase,attempts,last_error,payload,created_by,created_at,updated_at"
                + " FROM inv_operation WHERE tenant_id=? AND operation_no=?", SecurityUtils.tenantId(), operationNo);
        if (found.isEmpty()) throw BizException.notFound(operationNo);
        return found.getFirst();
    }

    private List<Row> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getString(7), rs.getString(8), rs.getString(9), ts(rs.getTimestamp(10)), ts(rs.getTimestamp(11))), args);
    }

    private List<Step> steps(String operationNo) {
        return jdbc.query("SELECT step_code,status,remote_ref,last_error,updated_at FROM inv_operation_step WHERE operation_no=? ORDER BY id",
                (rs, i) -> new Step(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), ts(rs.getTimestamp(5))), operationNo);
    }

    private Operation view(Row r) {
        return new Operation(r.operationNo(), r.kind(), r.bizRef(), r.phase(), r.attempts(), r.lastError(), r.createdBy(), r.createdAt(),
                r.updatedAt(), steps(r.operationNo()), retryable(r));
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
