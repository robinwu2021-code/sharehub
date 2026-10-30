package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.sharehub.api.ops.port.WorkOrderCommandPort;
import ai.neargo.sharehub.api.platform.dto.LocationOwnership;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.LocationQueryPort;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.dto.BindDtos.BindResult;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.movement.MovementEvent;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.dev.service.CabinetBindService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 绑定 / 解绑（V164）。见 {@link CabinetBindService} 的不变式。
 *
 * <h2>几条不显然的</h2>
 * <ul>
 *   <li><b>商家以站点为准</b>：点位上的 agent_no 是冗余，归属链取 {@link LocationQueryPort#ownershipOf}（读站点）。</li>
 *   <li><b>只许在库</b>：已上线 / 故障的设备换点位 = 搬机，先撤机。</li>
 *   <li><b>bound_at 置当前</b>：试借还结果早于它即失效，门禁会提示重做。</li>
 *   <li><b>条件更新核对行数</b>：两个人同时把同一台柜子绑到不同点位，后到的失败而不是覆盖。</li>
 * </ul>
 */
@Service
public class CabinetBindServiceImpl implements CabinetBindService {

    private static final Logger log = LoggerFactory.getLogger(CabinetBindServiceImpl.class);
    private static final int MAX_BATCH = 200;

    private final CabinetMapper cabinets;
    private final LocationQueryPort locations;
    private final AgentDirectoryPort agents;
    private final WorkOrderCommandPort workOrders;

    private final MovementRecorder movements;

    public CabinetBindServiceImpl(CabinetMapper cabinets, LocationQueryPort locations, AgentDirectoryPort agents,
                                  WorkOrderCommandPort workOrders, MovementRecorder movements) {
        this.movements = movements;
        this.cabinets = cabinets;
        this.locations = locations;
        this.agents = agents;
        this.workOrders = workOrders;
    }

    @Override
    @Transactional
    public BindResult bind(String cabinetNo, String locationNo, boolean openInstallWo, String expectedAt) {
        DevCabinet c = require(cabinetNo);
        if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus()) || c.getLocationNo() != null) {
            throw BizException.conflict("error.bind.not_bindable", cabinetNo);
        }
        if (c.getWarehouseNo() != null && c.getIssuedAgentNo() == null && isBlank(locationNo)) {
            // 在仓、未出库，又没指定点位：既没有出库池商家可取默认点位，也说不清要绑到哪
            throw BizException.conflict("error.bind.not_in_pool", cabinetNo);
        }
        String loc = isBlank(locationNo) ? defaultLocationOf(c, cabinetNo) : locationNo.trim();
        LocationOwnership own = ownershipOf(loc);
        requireSameMerchant(c, own);
        requireDestination(c, own);
        write(c, own);
        String woNo = openInstallWo ? workOrders.openInstall(cabinetNo, own.locationNo(), own.locationName(), expectedAt) : null;
        log.info("设备绑定 cabinetNo={} locationNo={} siteNo={} agentNo={} 装机单={}", cabinetNo, own.locationNo(),
                own.siteNo(), own.agentNo(), woNo);
        return new BindResult(CabinetServiceImpl.toVO(c), woNo);
    }

    @Override
    @Transactional
    public List<BindResult> bindBatch(String agentNo, String locationNo, List<String> cabinetNos, boolean openInstallWo,
                                      String expectedAt) {
        if (isBlank(agentNo)) throw BizException.badRequest("error.common.missing_parameter", "agentNo");
        List<String> nos = cabinetNos == null ? List.of()
                : cabinetNos.stream().filter(n -> !isBlank(n)).map(String::trim).distinct().toList();
        if (nos.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "deviceNos");
        if (nos.size() > MAX_BATCH) throw BizException.badRequest("error.common.invalid_value", "deviceNos > " + MAX_BATCH);
        String loc = isBlank(locationNo) ? defaultLocationOf(require(nos.get(0)), nos.get(0)) : locationNo.trim();
        List<BindResult> out = new ArrayList<>();
        for (String no : nos) {
            DevCabinet c = require(no);
            // 批量是「同一商家的一批」：混进别家的出库池设备当场拒，而不是绑完再靠 I2 拦
            if (!agentNo.equals(c.getIssuedAgentNo())) throw BizException.conflict("error.bind.agent_mismatch", no, c.getIssuedAgentNo());
            out.add(bind(no, loc, openInstallWo, expectedAt));
        }
        return out;
    }

    @Override
    @Transactional
    public Cabinet unbind(String cabinetNo, String reason) {
        if (isBlank(reason)) throw BizException.badRequest("error.common.reason_required");
        DevCabinet c = require(cabinetNo);
        if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus()) || c.getLocationNo() == null) {
            // 已上线的走撤机单（现场要拆机、清点）；没绑的本来就不用解
            throw BizException.conflict("error.bind.not_unbindable", cabinetNo);
        }
        int n = cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo).eq(DevCabinet::getLocationNo, c.getLocationNo())
                .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                .set(DevCabinet::getLocationNo, null).set(DevCabinet::getLocationName, null)
                .set(DevCabinet::getSiteNo, null).set(DevCabinet::getAgentNo, null));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        AuditChanges.record("所属点位", c.getLocationNo(), null);
        movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, cabinetNo, MovementEvent.UNBIND,
                MovementRecorder.loc(c.getLocationNo()), MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo()),
                c.getAgentNo(), null, reason.trim(), null));
        log.info("设备解绑回出库池 cabinetNo={} 原点位={} 出库池商家={} 原因={}", cabinetNo, c.getLocationNo(),
                c.getIssuedAgentNo(), reason.trim());
        c.setLocationNo(null);
        c.setLocationName(null);
        c.setSiteNo(null);
        c.setAgentNo(null);
        return CabinetServiceImpl.toVO(c);
    }

    @Override
    @Transactional
    public void rebindForInstall(String cabinetNo, String locationNo) {
        DevCabinet c = require(cabinetNo);
        if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) throw BizException.conflict("error.bind.not_bindable", cabinetNo);
        LocationOwnership own = ownershipOf(locationNo);
        requireSameMerchant(c, own);
        requireDestination(c, own);
        write(c, own);
    }

    // ── 内部 ──

    /** I2 / I5：点位的商家 = 出库池商家；直营点位（商家为空）只收不在任何出库池的设备。 */
    private static void requireSameMerchant(DevCabinet c, LocationOwnership own) {
        if (!Objects.equals(own.agentNo(), c.getIssuedAgentNo())) {
            throw BizException.conflict("error.bind.agent_mismatch", c.getCabinetNo(),
                    c.getIssuedAgentNo() == null ? "-" : c.getIssuedAgentNo());
        }
    }

    /**
     * PB-D4 / D2（V195）：出库池里的设备须已签收；出库到站点的只能绑该站点的点位。
     * 违反时让人回出库处调整目的地，而不是在绑定处绕过 —— 否则交接单与实物对不上。
     */
    private static void requireDestination(DevCabinet c, LocationOwnership own) {
        if (c.getIssuedAgentNo() == null) return;
        if (c.getIssueClaimedAt() == null) throw BizException.conflict("error.bind.not_claimed", c.getCabinetNo());
        if (c.getIssuedSiteNo() != null && !c.getIssuedSiteNo().equals(own.siteNo())) {
            throw BizException.conflict("error.bind.site_mismatch", c.getCabinetNo(), c.getIssuedSiteNo());
        }
    }

    private void write(DevCabinet c, LocationOwnership own) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<DevCabinet> u = new LambdaUpdateWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, c.getCabinetNo()).eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                .set(DevCabinet::getLocationNo, own.locationNo()).set(DevCabinet::getLocationName, own.locationName())
                .set(DevCabinet::getSiteNo, own.siteNo()).set(DevCabinet::getAgentNo, own.agentNo())
                .set(DevCabinet::getBoundAt, now)
                // 绑定即离开仓库（直营站点的设备从仓直接绑，I5）；出库池设备本来就不在仓
                .set(DevCabinet::getWarehouseNo, null);
        // 防并发：绑定时柜子必须仍未绑（改绑时必须仍是原点位）
        if (c.getLocationNo() == null) u.isNull(DevCabinet::getLocationNo); else u.eq(DevCabinet::getLocationNo, c.getLocationNo());
        if (cabinets.update(null, u) == 0) throw BizException.conflict("error.common.state_changed");
        AuditChanges.record("所属点位", c.getLocationNo(), own.locationNo());
        String from = c.getLocationNo() != null ? MovementRecorder.loc(c.getLocationNo())
                : c.getIssuedAgentNo() != null ? MovementRecorder.pool(c.getIssuedAgentNo(), c.getIssuedSiteNo())
                : MovementRecorder.wh(c.getWarehouseNo());
        movements.record(new MovementRecorder.Move(MovementRecorder.CABINET, c.getCabinetNo(), MovementEvent.BIND, from,
                MovementRecorder.loc(own.locationNo()), c.getAgentNo(), own.agentNo(), null, null));
        AuditChanges.record("归属商家", c.getAgentNo(), own.agentNo());
        c.setLocationNo(own.locationNo());
        c.setLocationName(own.locationName());
        c.setSiteNo(own.siteNo());
        c.setAgentNo(own.agentNo());
        c.setBoundAt(now);
        c.setWarehouseNo(null);
    }

    private LocationOwnership ownershipOf(String locationNo) {
        if (isBlank(locationNo)) throw BizException.badRequest("error.common.missing_parameter", "locationNo");
        LocationOwnership own = locations.ownershipOf(locationNo.trim());
        if (own == null) throw BizException.notFound(locationNo);
        return own;
    }

    /** 出库到的站点的默认点位；只到商家的，取商家默认站点的默认点位。 */
    private String defaultLocationOf(DevCabinet c, String cabinetNo) {
        String agentNo = c.getIssuedAgentNo();
        if (isBlank(agentNo)) throw BizException.conflict("error.bind.not_in_pool", cabinetNo);
        String site = c.getIssuedSiteNo() != null ? c.getIssuedSiteNo() : agents.defaultSiteNo(agentNo);
        String loc = locations.defaultLocationOf(site);
        if (loc == null) throw BizException.conflict("error.bind.no_default_point", agentNo);
        return loc;
    }

    private DevCabinet require(String cabinetNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        if (c.getArchivedAt() != null) throw BizException.conflict("error.common.archived_readonly");
        return c;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
