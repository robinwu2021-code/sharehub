package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.PowerbankStateMachine;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankRow;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.port.CabinetAvailabilitySource;
import ai.neargo.sharehub.dev.service.PowerbankLossService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 疑似丢失的标记、清除、升级与人工处置。
 *
 * <h2>打标记不能碰 updated_at</h2>
 * 「失联」的判定口径是 {@code status = RENTED AND updated_at < cutoff}（{@link CabinetAvailabilitySource#missingPowerbanks}）。
 * 打标记若顺带刷新了 updated_at（列上有 ON UPDATE），宝就立刻「不再失联」——
 * 已有的「宝失联」告警会跟着自动恢复，下一轮扫描又把标记当作不成立清掉。
 * 所以标记与升级都显式写 {@code updated_at = updated_at}；只有「已找回」才有意重置它。
 */
@Service
public class PowerbankLossServiceImpl implements PowerbankLossService {

    private static final Logger log = LoggerFactory.getLogger(PowerbankLossServiceImpl.class);
    /** 升级对象：运营（运维主管）角色，与工单无人可派时同一口径。 */
    private static final Set<String> OPS_LEAD_ROLES = Set.of("OPS");
    private static final int BATCH = 1000;

    private final PowerbankMapper powerbanks;
    private final CabinetAvailabilitySource availability;
    private final PowerbankStateMachine stateMachine;
    private final SysParamPort params;
    private final NotifyPort notify;
    private final EmployeeDirectoryPort employees;

    public PowerbankLossServiceImpl(PowerbankMapper powerbanks, CabinetAvailabilitySource availability,
                                    PowerbankStateMachine stateMachine, SysParamPort params,
                                    NotifyPort notify, EmployeeDirectoryPort employees) {
        this.powerbanks = powerbanks;
        this.availability = availability;
        this.stateMachine = stateMachine;
        this.params = params;
        this.notify = notify;
        this.employees = employees;
    }

    @Override
    @Transactional
    public ScanResult scan() {
        return DataScopeContext.executeWithoutScope(() -> {
            LocalDateTime now = LocalDateTime.now();
            int suspectDays = Math.max(1, params.intOf("asset.powerbank.suspect_lost_days", 7));
            int escalateDays = Math.max(1, params.intOf("asset.powerbank.lost_escalate_days", 30));

            // ① 新的疑似：失联满 N 天且还没打过标记
            Set<String> missing = availability.missingPowerbanks(now.minusDays(suspectDays), BATCH).stream()
                    .map(m -> m.powerbankNo()).collect(Collectors.toSet());
            int marked = 0;
            for (String no : missing) {
                marked += powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>()
                        .eq(DevPowerbank::getPowerbankNo, no).isNull(DevPowerbank::getSuspectedLostAt)
                        .set(DevPowerbank::getSuspectedLostAt, now)
                        .setSql("updated_at = updated_at"));
            }

            // ② 不再成立：宝已不是借出中（归还识别、买断、人工改了状态）→ 清标记
            int cleared = powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>()
                    .isNotNull(DevPowerbank::getSuspectedLostAt)
                    .ne(DevPowerbank::getStatus, PowerbankStatus.RENTED.name())
                    .set(DevPowerbank::getSuspectedLostAt, null)
                    .set(DevPowerbank::getLostEscalatedAt, null)
                    .setSql("updated_at = updated_at"));

            // ③ 久未处理：疑似满 M 天仍无人确认 / 找回 → 升级（每轮疑似只升级一次）
            List<DevPowerbank> stale = powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .eq(DevPowerbank::getStatus, PowerbankStatus.RENTED.name())
                    .lt(DevPowerbank::getSuspectedLostAt, now.minusDays(escalateDays))
                    .isNull(DevPowerbank::getLostEscalatedAt)
                    .orderByAsc(DevPowerbank::getSuspectedLostAt).last("limit " + BATCH));
            if (!stale.isEmpty()) {
                List<String> nos = stale.stream().map(DevPowerbank::getPowerbankNo).toList();
                String sample = String.join("、", nos.subList(0, Math.min(10, nos.size())));
                String content = "有 " + nos.size() + " 颗充电宝疑似丢失已超过 " + escalateDays + " 天无人核实：" + sample
                        + (nos.size() > 10 ? " 等" : "") + "。请在「设备管理 › 充电宝管理」筛选疑似丢失，确认丢失或标记已找回";
                var leads = employees.activeByRoles(OPS_LEAD_ROLES, 5);
                for (var lead : leads) notify.push(lead.employeeNo(), "POWERBANK_LOST_ESCALATED", content);
                if (leads.isEmpty()) {
                    log.warn("疑似丢失升级找不到运维主管 count={} sample={} —— 请为 OPS 角色配置在职员工，否则这批宝无人跟进", nos.size(), sample);
                }
                powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>()
                        .in(DevPowerbank::getPowerbankNo, nos)
                        .set(DevPowerbank::getLostEscalatedAt, now)
                        .setSql("updated_at = updated_at"));
            }
            if (marked + cleared + stale.size() > 0) {
                log.info("疑似丢失扫描 marked={} cleared={} escalated={} suspectDays={} escalateDays={}",
                        marked, cleared, stale.size(), suspectDays, escalateDays);
            }
            return new ScanResult(marked, cleared, stale.size());
        });
    }

    @Override
    @Transactional
    public PowerbankRow confirmLost(String powerbankNo, String note) {
        DevPowerbank e = requireSuspected(powerbankNo);
        String to = stateMachine.next(e.getStatus(), "CONFIRM_LOST");
        powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>()
                .eq(DevPowerbank::getId, e.getId())
                .eq(DevPowerbank::getStatus, e.getStatus())   // 并发：期间被归还识别了就别再标丢失
                .set(DevPowerbank::getStatus, to)
                .set(DevPowerbank::getCabinetNo, null).set(DevPowerbank::getSlotIndex, null)
                .set(DevPowerbank::getSuspectedLostAt, null).set(DevPowerbank::getLostEscalatedAt, null));
        DevPowerbank after = byNo(powerbankNo);
        if (!to.equals(after.getStatus())) {
            throw BizException.conflict("error.powerbank.lost_state_changed", powerbankNo);
        }
        log.info("充电宝确认丢失 powerbankNo={} operator={} note={}", powerbankNo, operator(), note);
        return PowerbankServiceImpl.toVO(after);
    }

    @Override
    @Transactional
    public PowerbankRow dismiss(String powerbankNo, String note) {
        if (note == null || note.isBlank()) throw BizException.badRequest("error.powerbank.dismiss_note_required");
        DevPowerbank e = requireSuspected(powerbankNo);
        // 有意刷新 updated_at：失联计时从现在重新算，否则下一轮扫描立刻又把它标回疑似
        powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>()
                .eq(DevPowerbank::getId, e.getId())
                .set(DevPowerbank::getSuspectedLostAt, null).set(DevPowerbank::getLostEscalatedAt, null)
                .set(DevPowerbank::getUpdatedAt, LocalDateTime.now()));
        log.info("充电宝疑似丢失已解除 powerbankNo={} operator={} note={}", powerbankNo, operator(), note);
        return PowerbankServiceImpl.toVO(byNo(powerbankNo));
    }

    private DevPowerbank requireSuspected(String powerbankNo) {
        DevPowerbank e = byNo(powerbankNo);
        if (e == null) throw BizException.notFound(powerbankNo);
        if (e.getSuspectedLostAt() == null) throw BizException.conflict("error.powerbank.not_suspected_lost", powerbankNo);
        return e;
    }

    private DevPowerbank byNo(String no) {
        return powerbanks.selectOne(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getPowerbankNo, no).last("limit 1"));
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }
}
