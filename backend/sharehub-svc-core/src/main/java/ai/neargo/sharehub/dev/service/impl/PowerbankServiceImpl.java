package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.dev.PowerbankStateMachine;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankCmd;
import ai.neargo.sharehub.dev.dto.DevDtos.PowerbankRow;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.PowerbankService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 充电宝业务实现（[db-design §9A.1]）。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li><b>状态只经状态机</b>：任何 status 变化都过 {@link PowerbankStateMachine}，非法迁移抛异常（主控接 409）；</li>
 *   <li><b>位置随状态同步</b>：离柜态（{@code IN_STOCK}/{@code RENTED}/{@code LOST}/{@code SOLD}/{@code SCRAP}）
 *       一律清空 {@code cabinetNo}/{@code slotIndex}，避免「已报废却还挂在某仓位」这类脏数据。</li>
 * </ol>
 */
@Service
public class PowerbankServiceImpl implements PowerbankService {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    private static final String TENANT_MAIN = "MAIN";

    private static final String INIT_STATUS = "IN_STOCK";

    private final PowerbankMapper mapper;
    private final PowerbankStateMachine stateMachine;

    public PowerbankServiceImpl(PowerbankMapper mapper, PowerbankStateMachine stateMachine) {
        this.mapper = mapper;
        this.stateMachine = stateMachine;
    }

    @Override
    public PageResult<PowerbankRow> page(Integer page, Integer size, String keyword, String status, String cabinetNo) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<DevPowerbank> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(DevPowerbank::getPowerbankNo, keyword).or().like(DevPowerbank::getSn, keyword));
        }
        if (status != null && !status.isBlank()) w.eq(DevPowerbank::getStatus, status);
        if (cabinetNo != null && !cabinetNo.isBlank()) w.eq(DevPowerbank::getCabinetNo, cabinetNo);
        w.orderByDesc(DevPowerbank::getId);

        Page<DevPowerbank> r = mapper.selectPage(new Page<>(p, s), w);
        List<PowerbankRow> rows = r.getRecords().stream().map(PowerbankServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public PowerbankRow get(String powerbankNo) {
        DevPowerbank e = selectByNo(powerbankNo);
        return e == null ? null : toVO(e);
    }

    @Override
    public PowerbankRow create(PowerbankCmd cmd) {
        DevPowerbank e = new DevPowerbank();
        e.setTenantId(TENANT_MAIN);
        e.setPowerbankNo(cmd.powerbankNo() == null || cmd.powerbankNo().isBlank()
                ? nextNo(BizKey.POWERBANK) : cmd.powerbankNo());
        e.setSn(cmd.sn());
        e.setVendorCode(cmd.vendorCode());
        e.setBattery(cmd.battery() == null ? 100 : cmd.battery());
        e.setCycles(cmd.cycles() == null ? 0 : cmd.cycles());
        e.setHealth(cmd.health() == null ? "OK" : cmd.health());
        // 建档即入库：起始态固定 IN_STOCK，不接受调用方指定（否则可绕过状态机凭空造出 RENTED）
        e.setStatus(INIT_STATUS);
        mapper.insert(e);
        return toVO(selectByNo(e.getPowerbankNo()));
    }

    @Override
    public PowerbankRow update(String powerbankNo, PowerbankCmd cmd) {
        DevPowerbank e = selectByNo(powerbankNo);
        if (e == null) throw BizException.notFound(powerbankNo);

        // —— 状态变更：event 优先，其次按目标 status 反查事件；两者都空表示只改属性 ——
        String event = cmd.event();
        if ((event == null || event.isBlank()) && cmd.status() != null && !cmd.status().isBlank()
                && !cmd.status().equals(e.getStatus())) {
            event = stateMachine.eventFor(e.getStatus(), cmd.status());
        }
        if (event != null && !event.isBlank()) {
            String to = stateMachine.next(e.getStatus(), event);
            e.setStatus(to);
            applyLocation(e, to, cmd);
        } else if (cmd.cabinetNo() != null) {
            e.setCabinetNo(cmd.cabinetNo());
            e.setSlotIndex(cmd.slotIndex());
        }

        if (cmd.sn() != null) e.setSn(cmd.sn());
        if (cmd.vendorCode() != null) e.setVendorCode(cmd.vendorCode());
        if (cmd.battery() != null) e.setBattery(cmd.battery());
        if (cmd.cycles() != null) e.setCycles(cmd.cycles());
        if (cmd.health() != null) e.setHealth(cmd.health());

        mapper.updateById(e);
        return toVO(selectByNo(powerbankNo));
    }

    /** 位置随生命周期同步：只有 {@code IN_CABINET}/{@code FAULT}(在柜待取) 保留柜位，其余清空。 */
    private static void applyLocation(DevPowerbank e, String to, PowerbankCmd cmd) {
        if ("IN_CABINET".equals(to)) {
            if (cmd.cabinetNo() != null) e.setCabinetNo(cmd.cabinetNo());
            if (cmd.slotIndex() != null) e.setSlotIndex(cmd.slotIndex());
        } else if (!"FAULT".equals(to)) {
            e.setCabinetNo(null);
            e.setSlotIndex(null);
        }
    }

    private DevPowerbank selectByNo(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<DevPowerbank>()
                .eq(DevPowerbank::getPowerbankNo, no).last("limit 1"));
    }

    /** 取号：扫描同前缀最大号 +1（[db-design §1.4.1]，禁止「前缀 + 数组长度」）。并发撞号由 UK 兜底。 */
    private String nextNo(String prefix) {
        DevPowerbank top = mapper.selectOne(new LambdaQueryWrapper<DevPowerbank>()
                .likeRight(DevPowerbank::getPowerbankNo, prefix)
                .orderByDesc(DevPowerbank::getPowerbankNo)
                .last("limit 1"));
        long n = 0L;
        if (top != null && top.getPowerbankNo() != null && top.getPowerbankNo().length() > prefix.length()) {
            String digits = top.getPowerbankNo().substring(prefix.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号
                }
            }
        }
        return prefix + String.format("%04d", n + 1);
    }

    private static PowerbankRow toVO(DevPowerbank e) {
        return new PowerbankRow(e.getPowerbankNo(), e.getSn(), e.getVendorCode(),
                e.getCabinetNo(), e.getSlotIndex(), e.getBattery(), e.getCycles(),
                e.getHealth(), e.getStatus(),
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }

    // ── 归档 / 取消归档（前端契约 Archivable）──
    // 本实现不走 AbstractCrudService（它有自己的业务规则），故在此实现同样语义：
    // archivedAt 时间戳，null=在用。**与 BaseEntity.deleted 是两回事**，见 Archivable。

    @Override
    @Transactional
    public PowerbankRow archive(String no) {
        return setArchived(no, java.time.LocalDateTime.now());
    }

    @Override
    @Transactional
    public PowerbankRow unarchive(String no) {
        return setArchived(no, null);
    }

    private PowerbankRow setArchived(String no, java.time.LocalDateTime at) {
        DevPowerbank e = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DevPowerbank>()
                .eq(DevPowerbank::getPowerbankNo, no).last("limit 1"));
        if (e == null) throw BizException.notFound(no);
        e.setArchivedAt(at);
        mapper.updateById(e);
        return toVO(e);
    }
}
