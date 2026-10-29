package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.QcStatus;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.dev.movement.MovementRecorder.Move;
import ai.neargo.sharehub.dev.service.CabinetPoolService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 出库池（V161，技术设计 §2.1 / §3.2 的「出库到商家」去掉调拨单后的直接动作）。
 *
 * <h2>几条不显然的</h2>
 * <ul>
 *   <li><b>条件更新 + 核对行数</b>：写入时 WHERE 里带上全部前置条件（在库、未绑定、未在出库池），
 *       行数不对就整批回滚 —— 两个人同时把同一台柜子出库给两个商家，后到的那个失败，而不是覆盖。</li>
 *   <li><b>出库即离开仓库</b>：{@code warehouse_no} 置空。库里有 CHECK 保证「在仓」与「在出库池」互斥（I1），
 *       所以这一步不能只写出库池不清仓。</li>
 *   <li><b>{@code agent_no} 不在这里写</b>：它只由绑定的点位派生（I3）。出库池里未绑定的设备 agent_no 为空，
 *       商家靠生成列 scope_agent_no 看到它们。</li>
 * </ul>
 */
@Service
public class CabinetPoolServiceImpl implements CabinetPoolService {

    private static final Logger log = LoggerFactory.getLogger(CabinetPoolServiceImpl.class);
    private static final int MAX_BATCH = 200;
    private static final String CABINET = MovementRecorder.CABINET;

    private final CabinetMapper cabinets;
    private final AgentDirectoryPort agents;
    private final ai.neargo.sharehub.dev.port.WarehouseLookup warehouseLookup;
    private final SiteQueryPort sites;
    private final EmployeeDirectoryPort employees;
    private final MovementRecorder movements;

    public CabinetPoolServiceImpl(CabinetMapper cabinets, AgentDirectoryPort agents,
                                  ai.neargo.sharehub.dev.port.WarehouseLookup warehouseLookup,
                                  SiteQueryPort sites, EmployeeDirectoryPort employees, MovementRecorder movements) {
        this.movements = movements;
        this.cabinets = cabinets;
        this.agents = agents;
        this.warehouseLookup = warehouseLookup;
        this.sites = sites;
        this.employees = employees;
    }

    @Override
    @Transactional
    public List<Cabinet> issue(String agentNo, String siteNo, String receiverNo, List<String> cabinetNos) {
        if (agentNo == null || agentNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "agentNo");
        List<String> nos = distinct(cabinetNos);
        if (!agents.readyForDevices(agentNo)) throw BizException.conflict("error.issue.agent_not_ready", agentNo);
        String site = requireSiteOf(agentNo, siteNo);
        String receiver = requireReceiver(receiverNo);

        String by = SecurityUtils.userNo();
        LocalDateTime now = LocalDateTime.now();
        List<Cabinet> out = new ArrayList<>();
        for (String no : nos) {
            DevCabinet c = require(no);
            if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus()) || c.getLocationNo() != null
                    || c.getIssuedAgentNo() != null) {
                throw BizException.conflict("error.issue.not_issuable", no);
            }
            if (!QcStatus.cleared(c.getQcStatus())) throw BizException.conflict("error.device.qc_not_passed", no);
            int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                    .eq(DevCabinet::getCabinetNo, no).eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                    .isNull(DevCabinet::getLocationNo).isNull(DevCabinet::getIssuedAgentNo)
                    .set(DevCabinet::getIssuedAgentNo, agentNo).set(DevCabinet::getIssuedAt, now)
                    .set(DevCabinet::getIssuedBy, by).set(DevCabinet::getWarehouseNo, null)
                    .set(DevCabinet::getIssuedSiteNo, site).set(DevCabinet::getIssueReceiverNo, receiver)
                    // 没指定接收人 = 经办人当面交付，出库即签收
                    .set(DevCabinet::getIssueClaimedAt, receiver == null ? now : null));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            AuditChanges.record("出库池", null, agentNo);
            AuditChanges.record("所在仓", c.getWarehouseNo(), null);
            movements.record(new Move(CABINET, no, MovementEvent.ISSUE, MovementRecorder.wh(c.getWarehouseNo()),
                    MovementRecorder.pool(agentNo, site), null, agentNo, receiver == null ? null : "接收人 " + receiver, null));
            c.setIssuedAgentNo(agentNo);
            c.setIssuedAt(now);
            c.setIssuedBy(by);
            c.setWarehouseNo(null);
            c.setIssuedSiteNo(site);
            c.setIssueReceiverNo(receiver);
            c.setIssueClaimedAt(receiver == null ? now : null);
            out.add(CabinetServiceImpl.toVO(c));
        }
        log.info("出库给商家 agentNo={} siteNo={} 接收人={} 台数={} 经办={}", agentNo, site, receiver, out.size(), by);
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> returnToWarehouse(List<String> cabinetNos, String warehouseNo, String reason) {
        if (reason == null || reason.isBlank()) throw BizException.badRequest("error.common.reason_required");
        List<String> nos = distinct(cabinetNos);
        // 不填仓 → 默认回仓仓；填了但不存在 → notFound（400）；一个仓都没有 → 409。
        // 此前不填就写 null：设备落到「在库、不在任何仓、也不在出库池」，在台账上等于失踪
        String wh;
        if (warehouseNo == null || warehouseNo.isBlank()) {
            wh = warehouseLookup.defaultReturnWarehouse();
            if (wh == null) throw BizException.conflict("error.issue.no_warehouse");
        } else {
            wh = warehouseNo.trim();
            if (!warehouseLookup.exists(wh)) throw BizException.notFound(wh);
        }
        List<Cabinet> out = new ArrayList<>();
        for (String no : nos) {
            DevCabinet c = require(no);
            if (c.getIssuedAgentNo() == null || c.getLocationNo() != null
                    || !CabinetStatus.IN_STOCK.name().equals(c.getStatus())) {
                // 已绑定的先解绑（绑定那一步再提供）；不在出库池的本来就不用退
                throw BizException.conflict("error.issue.not_in_pool", no);
            }
            int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                    .eq(DevCabinet::getCabinetNo, no).eq(DevCabinet::getIssuedAgentNo, c.getIssuedAgentNo())
                    .isNull(DevCabinet::getLocationNo)
                    .set(DevCabinet::getIssuedAgentNo, null).set(DevCabinet::getIssuedAt, null)
                    .set(DevCabinet::getIssuedBy, null).set(DevCabinet::getWarehouseNo, wh)
                    .set(DevCabinet::getIssuedSiteNo, null).set(DevCabinet::getIssueReceiverNo, null)
                    .set(DevCabinet::getIssueClaimedAt, null));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            AuditChanges.record("出库池", c.getIssuedAgentNo(), null);
            AuditChanges.record("所在仓", null, wh);
            movements.record(new Move(CABINET, no, MovementEvent.POOL_RETURN, MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo()),
                    MovementRecorder.wh(wh), c.getIssuedAgentNo(), null, reason.trim(), null));
            log.info("出库池退回 cabinetNo={} 原商家={} 回仓={} 原因={}", no, c.getIssuedAgentNo(), wh, reason.trim());
            c.setIssuedAgentNo(null);
            c.setIssuedAt(null);
            c.setIssuedBy(null);
            c.setWarehouseNo(wh);
            out.add(CabinetServiceImpl.toVO(c));
        }
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> claim(List<String> cabinetNos) {
        List<String> nos = distinct(cabinetNos);
        String me = SecurityUtils.userNo();
        LocalDateTime now = LocalDateTime.now();
        List<Cabinet> out = new ArrayList<>();
        for (String no : nos) {
            DevCabinet c = require(no);
            if (c.getIssuedAgentNo() == null || c.getIssueClaimedAt() != null) throw BizException.conflict("error.issue.not_claimable", no);
            // 本人 = 接收人：签收是交接凭证，别人代签等于没交接
            if (me == null || !me.equals(c.getIssueReceiverNo())) throw BizException.conflict("error.issue.not_receiver", no);
            int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, no)
                    .eq(DevCabinet::getIssuedAgentNo, c.getIssuedAgentNo()).eq(DevCabinet::getIssueReceiverNo, me)
                    .isNull(DevCabinet::getIssueClaimedAt).set(DevCabinet::getIssueClaimedAt, now));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            AuditChanges.record("签收", null, me);
            String at = MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo());
            movements.record(new Move(CABINET, no, MovementEvent.CLAIM, at, at, c.getIssuedAgentNo(), c.getIssuedAgentNo(), null, null));
            c.setIssueClaimedAt(now);
            out.add(CabinetServiceImpl.toVO(c));
        }
        log.info("出库签收 接收人={} 台数={}", me, out.size());
        return out;
    }

    @Override
    @Transactional
    public List<Cabinet> redirect(List<String> cabinetNos, String agentNo, String siteNo, String receiverNo, String reason) {
        if (reason == null || reason.isBlank()) throw BizException.badRequest("error.common.reason_required");
        if (agentNo == null || agentNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "agentNo");
        List<String> nos = distinct(cabinetNos);
        if (!agents.readyForDevices(agentNo)) throw BizException.conflict("error.issue.agent_not_ready", agentNo);
        String site = requireSiteOf(agentNo, siteNo);
        String receiver = requireReceiver(receiverNo);
        LocalDateTime now = LocalDateTime.now();
        List<Cabinet> out = new ArrayList<>();
        for (String no : nos) {
            DevCabinet c = require(no);
            if (c.getIssuedAgentNo() == null || c.getLocationNo() != null
                    || !CabinetStatus.IN_STOCK.name().equals(c.getStatus())) {
                throw BizException.conflict("error.issue.not_in_pool", no);
            }
            // 接收人没变且已签收的，保留签收；换了接收人（或改成指定接收人）要重新签
            boolean keepClaim = c.getIssueClaimedAt() != null && java.util.Objects.equals(receiver, c.getIssueReceiverNo());
            LocalDateTime claimed = receiver == null ? (c.getIssueClaimedAt() != null ? c.getIssueClaimedAt() : now)
                    : keepClaim ? c.getIssueClaimedAt() : null;
            int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, no)
                    .eq(DevCabinet::getIssuedAgentNo, c.getIssuedAgentNo()).isNull(DevCabinet::getLocationNo)
                    .set(DevCabinet::getIssuedAgentNo, agentNo).set(DevCabinet::getIssuedSiteNo, site)
                    .set(DevCabinet::getIssueReceiverNo, receiver).set(DevCabinet::getIssueClaimedAt, claimed));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            AuditChanges.record("出库池", c.getIssuedAgentNo(), agentNo);
            AuditChanges.record("出库站点", c.getIssuedSiteNo(), site);
            AuditChanges.record("接收人", c.getIssueReceiverNo(), receiver);
            movements.record(new Move(CABINET, no, MovementEvent.REDIRECT, MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo()),
                    MovementRecorder.pool(agentNo, site), c.getIssuedAgentNo(), agentNo, reason.trim(), null));
            log.info("出库调整 cabinetNo={} 商家 {}→{} 站点 {}→{} 接收人 {}→{} 原因={}", no, c.getIssuedAgentNo(), agentNo,
                    c.getIssuedSiteNo(), site, c.getIssueReceiverNo(), receiver, reason.trim());
            c.setIssuedAgentNo(agentNo);
            c.setIssuedSiteNo(site);
            c.setIssueReceiverNo(receiver);
            c.setIssueClaimedAt(claimed);
            out.add(CabinetServiceImpl.toVO(c));
        }
        return out;
    }

    /** 站点可空（到商家）；填了须属于该商家。 */
    private String requireSiteOf(String agentNo, String siteNo) {
        if (siteNo == null || siteNo.isBlank()) return null;
        String no = siteNo.trim();
        SiteBrief s = sites.briefsByNos(List.of(no)).stream().findFirst().orElseThrow(() -> BizException.notFound(no));
        if (!agentNo.equals(s.agentNo())) throw BizException.conflict("error.issue.site_not_of_agent", no, agentNo);
        return no;
    }

    /** 接收人可空；填了须是存在的员工。 */
    private String requireReceiver(String receiverNo) {
        if (receiverNo == null || receiverNo.isBlank()) return null;
        String no = receiverNo.trim();
        if (!employees.briefsOf(List.of(no)).containsKey(no)) throw BizException.notFound(no);
        return no;
    }

    @Override
    public List<IssuedSummary> summary(String agentNo) {
        LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<DevCabinet>()
                .isNotNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getArchivedAt)
                .ne(DevCabinet::getStatus, CabinetStatus.RETIRED.name());
        if (agentNo != null && !agentNo.isBlank()) w.eq(DevCabinet::getIssuedAgentNo, agentNo);
        Map<String, int[]> counts = new TreeMap<>();
        for (DevCabinet c : cabinets.selectList(w)) {
            int[] k = counts.computeIfAbsent(c.getIssuedAgentNo(), x -> new int[3]);
            if (c.getLocationNo() == null) k[0]++;
            else if (CabinetStatus.IN_STOCK.name().equals(c.getStatus())) k[1]++;
            else k[2]++;   // 已上线（含故障）
        }
        Map<String, AgentBrief> names = agents.briefsOf(counts.keySet());
        List<IssuedSummary> out = new ArrayList<>();
        counts.forEach((no, k) -> out.add(new IssuedSummary(no,
                names.containsKey(no) ? names.get(no).name() : null, k[0], k[1], k[2])));
        return out;
    }

    private DevCabinet require(String cabinetNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        if (c.getArchivedAt() != null) throw BizException.conflict("error.common.archived_readonly");
        return c;
    }

    private static List<String> distinct(List<String> nos) {
        List<String> out = nos == null ? List.of()
                : nos.stream().filter(n -> n != null && !n.isBlank()).map(String::trim).distinct().toList();
        if (out.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "cabinetNos");
        if (out.size() > MAX_BATCH) throw BizException.badRequest("error.common.invalid_value", "cabinetNos > " + MAX_BATCH);
        return out;
    }
}
