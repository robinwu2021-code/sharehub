package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.OkResult;
import ai.neargo.sharehub.finance.dto.FinDtos.ReconDiffRow;
import ai.neargo.sharehub.finance.dto.FinDtos.Reconcile;
import ai.neargo.sharehub.finance.entity.ReconDiff;
import ai.neargo.sharehub.finance.entity.ReconTask;
import ai.neargo.sharehub.finance.mapper.ReconDiffMapper;
import ai.neargo.sharehub.finance.mapper.ReconTaskMapper;
import ai.neargo.sharehub.finance.service.ReconcileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对账实现。批次由外部对账作业写入（本骨架不含生成逻辑，只提供查询与处置）。
 *
 * <p>处置动作**只翻 {@code resolved} 标记**，绝不回头修改 {@code acct_ledger}
 * —— 分录是只增表，账错了要用红冲分录纠正，见 {@code LedgerService}。
 */
@Service
public class ReconcileServiceImpl implements ReconcileService {

    private final ReconTaskMapper taskMapper;
    private final ReconDiffMapper diffMapper;

    public ReconcileServiceImpl(ReconTaskMapper taskMapper, ReconDiffMapper diffMapper) {
        this.taskMapper = taskMapper;
        this.diffMapper = diffMapper;
    }

    @Override
    public PageResult<Reconcile> pageTasks(Integer page, Integer size, String keyword,
                                           String status, String period) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<ReconTask> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(ReconTask::getBatchNo, kw).or().like(ReconTask::getChannel, kw));
        }
        if (status != null && !status.isBlank()) w.eq(ReconTask::getStatus, status);
        if (period != null && !period.isBlank()) w.eq(ReconTask::getPeriod, period);
        w.orderByDesc(ReconTask::getId);

        Page<ReconTask> r = taskMapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(ReconcileServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public List<ReconDiffRow> diffs(String batchNo) {
        requireTask(batchNo);
        return diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                        .eq(ReconDiff::getBatchNo, batchNo)
                        .orderByAsc(ReconDiff::getId))
                .stream()
                .map(d -> new ReconDiffRow(d.getId(), d.getBatchNo(), d.getPayNo(), d.getDiffType(),
                        d.getDetail(), d.getResolved() != null && d.getResolved() == 1))
                .toList();
    }

    /** 前端四个处置按钮 → 结论分类（ReconAction → ReconHandleResult）。 */
    private static final java.util.Map<String, String> ACTION_RESULT = java.util.Map.of(
            "verify", "VERIFIED_OK", "platform", "PLATFORM_ERROR",
            "channel", "CHANNEL_ERROR", "compensate", "COMPENSATED");

    @Override
    @Transactional
    public Reconcile resolve(String batchNo, Long diffId, String action, String handleNote, String operator) {
        requireTask(batchNo);
        // 处置分类与说明是留痕的主体（此前被静默丢弃，见 http.ts T0-2 注释）——空则拒
        String result = ACTION_RESULT.get(action == null ? "" : action);
        if (result == null) {
            throw new IllegalArgumentException("处置动作非法: " + action + "（仅 verify/platform/channel/compensate）");
        }
        if (handleNote == null || handleNote.isBlank()) {
            throw new IllegalArgumentException("处置说明 handleNote 必填（写清金额/凭证号/对接人）");
        }

        LambdaQueryWrapper<ReconDiff> w = new LambdaQueryWrapper<ReconDiff>().eq(ReconDiff::getBatchNo, batchNo);
        if (diffId != null) w.eq(ReconDiff::getId, diffId);
        // 只取**未处置**行（NULL 也算未处置，见下方计数处的同一理由）——
        // 不过滤的话，已平批次再点一次处置会再次「成功」并覆盖上一次的处置留痕，
        // 事后看不出这批差错究竟是被谁、以什么结论处置掉的。
        w.and(q -> q.isNull(ReconDiff::getResolved).or().ne(ReconDiff::getResolved, 1));
        List<ReconDiff> rows = diffMapper.selectList(w);
        if (rows.isEmpty()) throw new IllegalArgumentException("无待处置差错: " + batchNo);

        for (ReconDiff d : rows) {
            d.setResolved(1);
            diffMapper.updateById(d);
        }

        // 全部差错处置完 → 批次回到 MATCHED；否则维持 DIFF（半平不算平）
        // resolved 可能为 NULL（历史行未初始化），SQL 里 NULL <> 1 结果是 NULL 而非 true，
        // 只写 ne 会把未处置的 NULL 行漏掉、把批次误判成已平账，故显式带上 isNull 分支
        Long unresolved = diffMapper.selectCount(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getBatchNo, batchNo)
                .and(q -> q.isNull(ReconDiff::getResolved).or().ne(ReconDiff::getResolved, 1)));
        boolean allDone = unresolved == null || unresolved == 0;

        ReconTask t = requireTask(batchNo);
        if (allDone) t.setStatus("MATCHED");
        t.setHandleStatus(allDone ? "RESOLVED" : "HANDLING");
        t.setHandleResult(result);
        t.setHandleNote(handleNote.trim());
        t.setHandledBy(operator != null && !operator.isBlank() ? operator
                : ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                        .map(ai.neargo.sharehub.auth.LoginUser::username).orElse("system"));
        t.setHandledAt(java.time.LocalDateTime.now());
        taskMapper.updateById(t);
        return toVO(t);
    }

    private ReconTask requireTask(String batchNo) {
        ReconTask t = taskMapper.selectOne(new LambdaQueryWrapper<ReconTask>()
                .eq(ReconTask::getBatchNo, batchNo).last("limit 1"));
        if (t == null) throw new IllegalArgumentException("对账批次不存在: " + batchNo);
        return t;
    }

    private static Reconcile toVO(ReconTask e) {
        return new Reconcile(e.getBatchNo(), e.getChannel(), e.getPeriod(), e.getBillDate(),
                e.getNearpayTotal(), e.getLedgerTotal(), e.getDiff(), e.getCurrency(), e.getStatus(),
                ShareServiceImpl.fmt(e.getCreatedAt()),
                e.getHandleStatus(), e.getHandleResult(), e.getHandleNote(),
                e.getHandledBy(), ShareServiceImpl.fmt(e.getHandledAt()));
    }

    @Override
    public java.util.Map<String, Object> stats(String period) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReconTask> w =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (period != null && !period.isBlank()) w.eq("period", period);
        long tasks = taskMapper.selectCount(w);
        long diffs = diffMapper.selectCount(null);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReconDiff> rw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        rw.eq("status", "RESOLVED");
        long resolved = diffMapper.selectCount(rw);
        // 差异数与已处理数分开给，前端才能算「还有多少没对平」——
        // 只给一个「未处理数」的话，页面无法显示进度分母。
        return java.util.Map.of("tasks", tasks, "diffs", diffs, "resolved", resolved,
                "pending", Math.max(0, diffs - resolved));
    }
}
