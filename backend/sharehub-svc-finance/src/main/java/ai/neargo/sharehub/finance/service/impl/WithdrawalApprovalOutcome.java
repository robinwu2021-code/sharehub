package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.api.platform.flow.ApprovalOutcomeHandler;
import ai.neargo.sharehub.finance.WithdrawalApprovalState;
import ai.neargo.sharehub.finance.entity.StlWithdrawal;
import ai.neargo.sharehub.finance.mapper.StlWithdrawalMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 提现的 OpsKit 审批回写（M5-1 试点，方案 23 §9.2）。
 *
 * <p>三个回调都可能重复到达（至少一次投递）：<b>只在 approval_state 与轮次对得上时才动</b>，
 * 其余一律当作「已处理过 / 过期消息」记日志后返回 —— 不抛异常，抛了 OpsKit 会一直重投。
 *
 * <p>审批结论复用 {@link WithdrawalServiceImpl#applyDecision}：与本地审批同一段业务规则，
 * 审批人取 OpsKit 回传的宿主编号（回写时没有登录人）。
 */
@Component
public class WithdrawalApprovalOutcome implements ApprovalOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalApprovalOutcome.class);

    private final StlWithdrawalMapper mapper;
    private final WithdrawalServiceImpl service;

    public WithdrawalApprovalOutcome(StlWithdrawalMapper mapper, WithdrawalServiceImpl service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public String bizType() {
        return WithdrawalServiceImpl.BIZ_TYPE;
    }

    @Override
    @Transactional
    public void onSubmitted(String bizNo, int round, String instanceNo, String approvalNo) {
        StlWithdrawal e = find(bizNo);
        if (e == null || !isState(e, WithdrawalApprovalState.SUBMITTING) || !Objects.equals(e.getApprovalRound(), round)) {
            log.info("送审回填跳过（已处理或轮次不符）withdrawNo={} round={} state={}", bizNo, round, e == null ? null : e.getApprovalState());
            return;
        }
        e.setApprovalState(WithdrawalApprovalState.IN_REVIEW.name());
        e.setApprovalNo(approvalNo);
        mapper.updateById(e);
    }

    @Override
    @Transactional
    public void onSubmitFailed(String bizNo, int round, String error) {
        StlWithdrawal e = find(bizNo);
        if (e == null || !isState(e, WithdrawalApprovalState.SUBMITTING) || !Objects.equals(e.getApprovalRound(), round)) return;
        e.setApprovalState(WithdrawalApprovalState.SUBMIT_FAILED.name());
        e.setApprovalNote(truncate(error));
        mapper.updateById(e);
        // 谁该做什么：财务在「提现审核」页点「重新送审」；若反复失败，先查 OpsKit 是否可用
        log.error("提现送审失败（已转死信）withdrawNo={} round={}：{} —— 财务可在提现审核页重新送审", bizNo, round, error);
    }

    @Override
    @Transactional
    public void onCompleted(String bizNo, ApprovalDecision d) {
        StlWithdrawal e = find(bizNo);
        // SUBMITTING 也收：OpsKit 建单成功但回填还没落（或回填消息晚到），结论先到了
        if (e == null || !(isState(e, WithdrawalApprovalState.IN_REVIEW) || isState(e, WithdrawalApprovalState.SUBMITTING))) {
            log.warn("审批结论不再适用，未回写 withdrawNo={} result={} state={}（多为重复消息，或切换期已在本地处理）",
                    bizNo, d.result(), e == null ? null : e.getApprovalState());
            return;
        }
        if (e.getApprovalNo() != null && d.approvalNo() != null && !e.getApprovalNo().equals(d.approvalNo())) {
            log.warn("审批结论来自旧审批单，未回写 withdrawNo={} 当前={} 来自={}", bizNo, e.getApprovalNo(), d.approvalNo());
            return;
        }
        if (d.approvalNo() != null) e.setApprovalNo(d.approvalNo());
        if (d.deciderNo() == null) {
            log.warn("OpsKit 审批结论没带决定人宿主编号 withdrawNo={} approvalNo={}（审批人一栏将为空）", bizNo, d.approvalNo());
        }
        boolean clear = false;
        if (d.approved()) {
            try {
                service.applyDecision(e, true, null, d.deciderNo(), d.deciderName());
                clear = true;
            } catch (IllegalArgumentException | ai.neargo.sharehub.common.BizException ex) {
                // 批了但生效不了：记下决定人（重试生效时仍算他批的），状态停在 APPLY，等人补齐
                e.setAuditorNo(d.deciderNo());
                e.setAuditorName(d.deciderName());
                e.setApprovalState(WithdrawalApprovalState.APPROVED_BLOCKED.name());
                e.setApprovalNote(truncate(ex.getMessage()));
                log.error("提现审批已通过但未能生效 withdrawNo={} approvalNo={}：{} —— 财务补齐后在提现审核页点「重试生效」",
                        bizNo, d.approvalNo(), ex.getMessage());
            }
        } else {
            // 驳回与撤回都落「驳回」：钱不出去；撤回的原因写明是申请人撤回
            String reason = d.reason() != null && !d.reason().isBlank() ? d.reason()
                    : ("WITHDRAWN".equals(d.result()) ? "申请人已撤回" : "审批驳回");
            service.applyDecision(e, false, reason, d.deciderNo(), d.deciderName());
            clear = true;
        }
        mapper.updateById(e);
        if (clear) service.clearApprovalProgress(bizNo);   // null 不能靠 updateById 写（MyBatis-Plus 跳过 null 字段）
    }

    private StlWithdrawal find(String no) {
        return mapper.selectOne(new LambdaQueryWrapper<StlWithdrawal>().eq(StlWithdrawal::getWithdrawNo, no).last("LIMIT 1"));
    }

    private static boolean isState(StlWithdrawal e, WithdrawalApprovalState s) {
        return s.name().equals(e.getApprovalState());
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() <= 250 ? s : s.substring(0, 250));
    }
}
