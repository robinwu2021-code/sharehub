package ai.neargo.sharehub.agent.apply.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyResult;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.ApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.AuditReq;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.MyApplyView;
import ai.neargo.sharehub.agent.apply.dto.ApplyDtos.SubmitReq;

/**
 * 入驻申请：商家自助注册与运营商代建**同落一条链**（ADR-030 §三）。
 *
 * <p>状态机 {@code DRAFT → SUBMITTED → REVIEWING → APPROVED / REJECTED}，
 * <b>在本层强制，非法迁移抛错</b>（本仓既有约定）。
 *
 * <p>「条件相同」的三个落点都在实现里：同一张表、同一个状态机、
 * 同一套必填校验（必填由 {@code operatorType} 决定，<b>不由 source 决定</b>）。
 */
public interface ApplyService {

    /**
     * 提交申请。
     *
     * @param staffNo 经办员工业务键；<b>非 null 即代建</b>（{@code OPS_CREATED}），
     *                null 即自助（{@code SELF_SERVICE}，此时校验 OTP）
     */
    ApplyResult submit(SubmitReq req, String staffNo);

    /** 申请人查自己的进度与驳回原因（凭手机号 + OTP）。 */
    MyApplyView mine(String phone, String otp);

    /** 运营端队列与历史检索。{@code status} 为空时返回在途（SUBMITTED + REVIEWING）。 */
    PageResult<ApplyView> search(String status, String keyword,
                                 String from, String to, Integer page, Integer size);

    /** 受理：SUBMITTED → REVIEWING。 */
    ApplyResult accept(String applyNo, String staffNo);

    /**
     * 审核：通过则在<b>一个事务</b>里派生主体 + 自然人 + 属主成员关系行并回写 {@code operatorNo}。
     *
     * <p>代建场景允许同一人录入并放行，但状态机照走、{@code reviewedBy} 照写 —— 审计链不能断。
     */
    ApplyResult audit(String applyNo, AuditReq req, String staffNo);
}
