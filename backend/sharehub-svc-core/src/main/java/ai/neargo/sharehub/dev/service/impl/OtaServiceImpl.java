package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaReleaseRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaRolloutRow;
import ai.neargo.sharehub.dev.dto.DevDtos.OtaTaskRow;
import ai.neargo.sharehub.dev.entity.DevOtaRelease;
import ai.neargo.sharehub.dev.entity.DevOtaRollout;
import ai.neargo.sharehub.dev.entity.DevOtaTask;
import ai.neargo.sharehub.dev.mapper.OtaReleaseMapper;
import ai.neargo.sharehub.dev.mapper.OtaRolloutMapper;
import ai.neargo.sharehub.dev.mapper.OtaTaskMapper;
import ai.neargo.sharehub.dev.service.OtaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固件 OTA 实现。
 *
 * <p>投放状态迁移 {@code PENDING→RUNNING→DONE} + {@code RUNNING→ROLLBACK}
 * （[db-design §3.1]）在本类显式校验：投放只有 4 态、无跨状态分支，单独立一个状态机组件
 * 反而比就地校验更难读；充电宝那种 7 态多分支的才值得独立组件。
 */
@Service
public class OtaServiceImpl implements OtaService {

    /** 单运营方，隔离键恒为 MAIN（ADR-011 休眠口子）。 */
    private static final String TENANT_MAIN = "MAIN";

    /**
     * 固件版本业务键前缀。
     * <p><b>注意</b>：{@code BizKey} 目前只登记了投放的 {@code OTA}，没有版本前缀（见交付报告的规格缺漏）。
     * 此处暂用 {@code FW}，与既有前缀均不冲突；{@code BizKey} 补登记后改引用常量。
     */
    private static final String RELEASE_PREFIX = "FW";

    /** 投放合法迁移：from → 可达 to。 */
    private static final Map<String, Set<String>> ROLLOUT_TRANSITIONS = Map.of(
            "PENDING", Set.of("RUNNING", "ROLLBACK"),
            "RUNNING", Set.of("DONE", "ROLLBACK"),
            "DONE", Set.of("ROLLBACK"),   // 全量完成后仍可整体回滚
            "ROLLBACK", Set.of());        // 终态

    private final OtaReleaseMapper releaseMapper;
    private final OtaRolloutMapper rolloutMapper;
    private final OtaTaskMapper taskMapper;

    public OtaServiceImpl(OtaReleaseMapper releaseMapper, OtaRolloutMapper rolloutMapper,
                          OtaTaskMapper taskMapper) {
        this.releaseMapper = releaseMapper;
        this.rolloutMapper = rolloutMapper;
        this.taskMapper = taskMapper;
    }

    // ——————————————————————— 固件版本 ———————————————————————

    @Override
    public PageResult<OtaReleaseRow> releases(Integer page, Integer size, String keyword,
                                              String fwType, String vendorCode, String status) {
        LambdaQueryWrapper<DevOtaRelease> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(DevOtaRelease::getReleaseNo, keyword).or().like(DevOtaRelease::getVersion, keyword));
        }
        if (fwType != null && !fwType.isBlank()) w.eq(DevOtaRelease::getFwType, fwType);
        if (vendorCode != null && !vendorCode.isBlank()) w.eq(DevOtaRelease::getVendorCode, vendorCode);
        if (status != null && !status.isBlank()) w.eq(DevOtaRelease::getStatus, status);
        w.orderByDesc(DevOtaRelease::getVersionCode);

        Page<DevOtaRelease> r = releaseMapper.selectPage(new Page<>(norm(page), normSize(size)), w);
        return new PageResult<>(r.getRecords().stream().map(OtaServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public OtaReleaseRow saveRelease(DevOtaRelease body) {
        String no = body.getReleaseNo();
        if (no == null || no.isBlank()) {
            body.setReleaseNo(nextReleaseNo());
            if (body.getTenantId() == null) body.setTenantId(TENANT_MAIN);
            if (body.getStatus() == null || body.getStatus().isBlank()) body.setStatus("DRAFT");
            if (body.getMandatory() == null) body.setMandatory(0);
            releaseMapper.insert(body);
            return toVO(selectRelease(body.getReleaseNo()));
        }
        DevOtaRelease cur = selectRelease(no);
        if (cur == null) {
            if (body.getTenantId() == null) body.setTenantId(TENANT_MAIN);
            if (body.getStatus() == null || body.getStatus().isBlank()) body.setStatus("DRAFT");
            releaseMapper.insert(body);
        } else {
            body.setId(cur.getId());
            body.setLockVersion(cur.getLockVersion());
            if (body.getTenantId() == null) body.setTenantId(cur.getTenantId());
            releaseMapper.updateById(body);
        }
        return toVO(selectRelease(no));
    }

    // ——————————————————————— 投放 ———————————————————————

    @Override
    public PageResult<OtaRolloutRow> rollouts(Integer page, Integer size, String keyword,
                                              String releaseNo, String status, String strategy) {
        LambdaQueryWrapper<DevOtaRollout> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(DevOtaRollout::getRolloutNo, keyword).or().like(DevOtaRollout::getFwVersion, keyword));
        }
        if (releaseNo != null && !releaseNo.isBlank()) w.eq(DevOtaRollout::getReleaseNo, releaseNo);
        if (status != null && !status.isBlank()) w.eq(DevOtaRollout::getStatus, status);
        if (strategy != null && !strategy.isBlank()) w.eq(DevOtaRollout::getStrategy, strategy);
        w.orderByDesc(DevOtaRollout::getId);

        Page<DevOtaRollout> r = rolloutMapper.selectPage(new Page<>(norm(page), normSize(size)), w);
        return new PageResult<>(r.getRecords().stream().map(OtaServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public OtaRolloutRow rollout(String rolloutNo) {
        DevOtaRollout e = selectRollout(rolloutNo);
        return e == null ? null : toVO(e);
    }

    @Override
    public OtaRolloutRow saveRollout(DevOtaRollout body) {
        String no = body.getRolloutNo();
        if (no == null || no.isBlank()) {
            body.setRolloutNo(nextRolloutNo());
            if (body.getStatus() == null || body.getStatus().isBlank()) body.setStatus("PENDING");
            if (body.getStrategy() == null || body.getStrategy().isBlank()) body.setStrategy("GRAY");
            if (body.getScope() == null || body.getScope().isBlank()) body.setScope("ALL");
            if (body.getProgress() == null) body.setProgress(0);
            fillFwVersion(body);
            rolloutMapper.insert(body);
            return toVO(selectRollout(body.getRolloutNo()));
        }

        DevOtaRollout cur = selectRollout(no);
        if (cur == null) throw new IllegalArgumentException("OTA 投放不存在: " + no);
        if (body.getStatus() != null && !body.getStatus().isBlank() && !body.getStatus().equals(cur.getStatus())) {
            checkRolloutTransition(cur.getStatus(), body.getStatus());
            cur.setStatus(body.getStatus());
        }
        if (body.getStrategy() != null) cur.setStrategy(body.getStrategy());
        if (body.getScope() != null) cur.setScope(body.getScope());
        if (body.getTargetRef() != null) cur.setTargetRef(body.getTargetRef());
        if (body.getProgress() != null) cur.setProgress(body.getProgress());
        rolloutMapper.updateById(cur);
        return toVO(selectRollout(no));
    }

    @Override
    public List<OtaTaskRow> tasks(String rolloutNo) {
        return taskMapper.selectList(new LambdaQueryWrapper<DevOtaTask>()
                        .eq(DevOtaTask::getRolloutNo, rolloutNo)
                        .orderByAsc(DevOtaTask::getId))
                .stream().map(OtaServiceImpl::toVO).toList();
    }

    // ——————————————————————— 内部 ———————————————————————

    /** 投放状态迁移校验；非法迁移抛异常（主控后续接 409）。 */
    private static void checkRolloutTransition(String from, String to) {
        Set<String> allowed = ROLLOUT_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException("OTA 投放状态非法迁移: " + from + " --> " + to);
        }
    }

    /** 投放冗余固件版本号：调用方没给就按 releaseNo 回填，保证列表免联表。 */
    private void fillFwVersion(DevOtaRollout body) {
        if (body.getFwVersion() != null && !body.getFwVersion().isBlank()) return;
        DevOtaRelease rel = selectRelease(body.getReleaseNo());
        if (rel != null) {
            body.setFwVersion(rel.getVersion());
            if (body.getVendorCode() == null) body.setVendorCode(rel.getVendorCode());
        }
    }

    private DevOtaRelease selectRelease(String no) {
        if (no == null || no.isBlank()) return null;
        return releaseMapper.selectOne(new LambdaQueryWrapper<DevOtaRelease>()
                .eq(DevOtaRelease::getReleaseNo, no).last("limit 1"));
    }

    private DevOtaRollout selectRollout(String no) {
        if (no == null || no.isBlank()) return null;
        return rolloutMapper.selectOne(new LambdaQueryWrapper<DevOtaRollout>()
                .eq(DevOtaRollout::getRolloutNo, no).last("limit 1"));
    }

    /** 取号：扫描同前缀最大号 +1（[db-design §1.4.1]）。并发撞号由 UK 兜底。 */
    private String nextReleaseNo() {
        DevOtaRelease top = releaseMapper.selectOne(new LambdaQueryWrapper<DevOtaRelease>()
                .likeRight(DevOtaRelease::getReleaseNo, RELEASE_PREFIX)
                .orderByDesc(DevOtaRelease::getReleaseNo).last("limit 1"));
        return RELEASE_PREFIX + seq(top == null ? null : top.getReleaseNo(), RELEASE_PREFIX);
    }

    private String nextRolloutNo() {
        DevOtaRollout top = rolloutMapper.selectOne(new LambdaQueryWrapper<DevOtaRollout>()
                .likeRight(DevOtaRollout::getRolloutNo, BizKey.OTA_ROLLOUT)
                .orderByDesc(DevOtaRollout::getRolloutNo).last("limit 1"));
        return BizKey.OTA_ROLLOUT + seq(top == null ? null : top.getRolloutNo(), BizKey.OTA_ROLLOUT);
    }

    private static String seq(String topKey, String prefix) {
        long n = 0L;
        if (topKey != null && topKey.length() > prefix.length()) {
            String digits = topKey.substring(prefix.length()).replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                try {
                    n = Long.parseLong(digits);
                } catch (NumberFormatException ignore) {
                    // 历史脏号不参与取号
                }
            }
        }
        return String.format("%04d", n + 1);
    }

    private static int norm(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    private static int normSize(Integer size) {
        return (size == null || size < 1) ? 10 : Math.min(size, 200);
    }

    private static OtaReleaseRow toVO(DevOtaRelease e) {
        return new OtaReleaseRow(e.getReleaseNo(), e.getFwType(), e.getVendorCode(), e.getVersion(),
                e.getVersionCode(), e.getArtifactUrl(), e.getChecksum(),
                e.getMandatory() != null && e.getMandatory() == 1, e.getStatus(), e.getReleaseNotes());
    }

    private static OtaRolloutRow toVO(DevOtaRollout e) {
        return new OtaRolloutRow(e.getRolloutNo(), e.getReleaseNo(), e.getFwVersion(), e.getVendorCode(),
                e.getStrategy(), e.getScope(), e.getTargetRef(), e.getProgress(), e.getStatus(),
                e.getCreatedAt());
    }

    private static OtaTaskRow toVO(DevOtaTask e) {
        return new OtaTaskRow(e.getTaskNo(), e.getRolloutNo(), e.getCabinetNo(), e.getStatus(),
                e.getProgress(), e.getPreviousVersion(), e.getError());
    }
}
