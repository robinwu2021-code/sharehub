package ai.neargo.sharehub.portal.finance;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutConfirmReq;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutExportFile;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutPreview;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTask;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTaskAuditReq;
import ai.neargo.sharehub.finance.dto.FinDtos.PayoutTaskCreateReq;
import ai.neargo.sharehub.finance.service.PayoutTaskService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;

/**
 * 付款任务（商家一期 B4，需求方案 §八）：平台向商家付款，财务在运营端手动执行。
 *
 * <p>发起、复核、确认三步三个码（{@code :create} / {@code :audit} / {@code :pay}），导出单独一个码
 * （{@code :export}）—— 转账文件含完整 IBAN，只给少数人。
 */
@RestController
public class PayoutTaskController {

    private final PayoutTaskService service;

    public PayoutTaskController(PayoutTaskService service) {
        this.service = service;
    }

    /** 预览：选定截止日后每个商家能付多少、哪些本次不能付及原因。只读，不占结算单。 */
    @GetMapping("/api/trade/payout-tasks/preview")
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public PayoutPreview preview(@RequestParam String cutoffDate) {
        return service.preview(date(cutoffDate));
    }

    @PostMapping("/api/trade/payout-tasks")
    @PreAuthorize("@perm.can('finance:withdrawal:create')")
    public PayoutTask create(@RequestBody PayoutTaskCreateReq req) {
        return service.create(date(req == null ? null : req.cutoffDate()));
    }

    @GetMapping("/api/trade/payout-tasks")
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public PageResult<PayoutTask> page(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status) {
        return service.page(page, size, status);
    }

    @GetMapping("/api/trade/payout-tasks/{taskNo}")
    @PreAuthorize("@perm.can('finance:withdrawal:read')")
    public PayoutTask detail(@PathVariable String taskNo) {
        return service.detail(taskNo);
    }

    @PostMapping("/api/trade/payout-tasks/{taskNo}/audit")
    @PreAuthorize("@perm.can('finance:withdrawal:audit')")
    public PayoutTask audit(@PathVariable String taskNo, @RequestBody PayoutTaskAuditReq req) {
        return service.audit(taskNo, req != null && Boolean.TRUE.equals(req.approve()),
                req == null ? null : req.rejectReason());
    }

    /**
     * 转账文件下载。直接写响应 —— 统一响应信封只适合 JSON（同对账单的做法）。
     * 先生成再写：缺完整账号时抛业务异常走统一错误处理，而不是写半个文件。
     */
    @GetMapping("/api/trade/payout-tasks/{taskNo}/export")
    @PreAuthorize("@perm.can('finance:withdrawal:export')")
    public void export(@PathVariable String taskNo, HttpServletResponse response) throws IOException {
        PayoutExportFile f = service.export(taskNo);
        response.setContentType(f.contentType());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + f.filename() + "\"");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(f.content());
    }

    @PostMapping("/api/trade/payout-tasks/{taskNo}/confirm")
    @PreAuthorize("@perm.can('finance:withdrawal:pay')")
    public PayoutTask confirm(@PathVariable String taskNo, @RequestBody PayoutConfirmReq req) {
        return service.confirm(taskNo, req);
    }

    private static LocalDate date(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "cutoffDate");
        try {
            return LocalDate.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.badRequest("error.common.invalid_value", v);
        }
    }
}
