package ai.neargo.sharehub.user.member.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreAdjustReq;
import ai.neargo.sharehub.user.member.dto.MemberDtos.CreditScoreChange;
import ai.neargo.sharehub.user.member.entity.UsrCreditChange;
import ai.neargo.sharehub.user.member.mapper.UsrCreditChangeMapper;
import ai.neargo.sharehub.user.member.service.CreditScoreService;
import ai.neargo.sharehub.user.core.entity.UsrCredit;
import ai.neargo.sharehub.user.core.mapper.UserCoreMappers.UsrCreditMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 信用分调整实现。
 *
 * <p><b>改分与落流水必须同事务</b>：只改分不落流水，「分是怎么变成今天这样的」
 * 就永远查不出来 —— 而这恰恰是风控争议时唯一能拿出来的东西。
 */
@Service
public class CreditScoreServiceImpl implements CreditScoreService {

    private static final String TENANT_MAIN = "MAIN";
    /** 信用分区间。超出即拒 —— 让分数跑到 -50 或 300 会让所有基于阈值的风控规则失效。 */
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private final UsrCreditChangeMapper changeMapper;
    private final UsrCreditMapper creditMapper;

    public CreditScoreServiceImpl(UsrCreditChangeMapper changeMapper, UsrCreditMapper creditMapper) {
        this.changeMapper = changeMapper;
        this.creditMapper = creditMapper;
    }

    @Override
    @Transactional
    public CreditScoreChange adjust(String cUserNo, CreditScoreAdjustReq req) {
        if (req == null || req.delta() == null || req.delta() == 0) {
            throw new IllegalArgumentException("调分幅度必须非零");
        }
        if (req.reason() == null || req.reason().isBlank()) {
            // 没有原因的调分等于没有记录 —— 风控争议时无法解释这分是怎么来的。
            throw new IllegalArgumentException("调整信用分必须填写原因");
        }
        UsrCredit c = creditMapper.selectOne(new LambdaQueryWrapper<UsrCredit>()
                .eq(UsrCredit::getCUserNo, cUserNo).last("limit 1"));
        if (c == null) {
            throw BizException.notFound(cUserNo);
        }
        int before = c.getScore() == null ? 0 : c.getScore();
        int after = before + req.delta();
        if (after < MIN_SCORE || after > MAX_SCORE) {
            // 钳制而不是静默截断：截断会让运营以为加了 20 分，实际只加了 5 分。
            throw new IllegalArgumentException(String.format(
                    "调整后分数 %d 超出区间 [%d, %d]，当前 %d", after, MIN_SCORE, MAX_SCORE, before));
        }

        c.setScore(after);
        creditMapper.updateById(c);

        UsrCreditChange e = new UsrCreditChange();
        e.setChangeNo("CSC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        e.setTenantId(c.getTenantId() == null ? TENANT_MAIN : c.getTenantId());
        e.setCUserNo(cUserNo);
        e.setScoreBefore(before);
        e.setScoreAfter(after);
        e.setDelta(req.delta());
        e.setReason(req.reason().trim());
        // 操作人取登录态，不接受入参 —— 与提现审批同一条红线：信前端就可伪造留痕。
        e.setOperatorName(SecurityUtils.currentUser().map(LoginUser::username).orElse(null));
        e.setCreatedAt(LocalDateTime.now());
        changeMapper.insert(e);
        return toVO(e);
    }

    @Override
    public PageResult<CreditScoreChange> pageChanges(Integer page, Integer size, String cUserNo) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);
        LambdaQueryWrapper<UsrCreditChange> w = new LambdaQueryWrapper<>();
        if (cUserNo != null && !cUserNo.isBlank()) w.eq(UsrCreditChange::getCUserNo, cUserNo);
        w.orderByDesc(UsrCreditChange::getId);
        Page<UsrCreditChange> r = changeMapper.selectPage(new Page<>(p, s), w);
        List<CreditScoreChange> rows = r.getRecords().stream()
                .map(CreditScoreServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static CreditScoreChange toVO(UsrCreditChange e) {
        return new CreditScoreChange(e.getChangeNo(), e.getCUserNo(), e.getScoreBefore(),
                e.getScoreAfter(), e.getDelta(), e.getReason(), e.getOperatorName(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
