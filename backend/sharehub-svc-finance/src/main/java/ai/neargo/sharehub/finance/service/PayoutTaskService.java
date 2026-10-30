package ai.neargo.sharehub.finance.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutConfirmReq;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutExportFile;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutPreview;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTask;

import java.time.LocalDate;

/**
 * 付款任务（商家一期 B4，需求方案 §八）：平台向商家付款，财务在运营端手动执行，一般三个月一次。
 *
 * <p>选截止日 → {@link #preview} → {@link #create} → {@link #audit}（复核人 ≠ 发起人）
 * → {@link #export}（含完整 IBAN）→ 线下银行转账 → {@link #confirm}。全程不接支付渠道，只做记账。
 */
public interface PayoutTaskService {

    PayoutPreview preview(LocalDate cutoffDate);

    PayoutTask create(LocalDate cutoffDate);

    PageResult<PayoutTask> page(Integer page, Integer size, String status);

    PayoutTask detail(String taskNo);

    PayoutTask audit(String taskNo, boolean approve, String rejectReason);

    PayoutExportFile export(String taskNo);

    PayoutTask confirm(String taskNo, PayoutConfirmReq req);
}
