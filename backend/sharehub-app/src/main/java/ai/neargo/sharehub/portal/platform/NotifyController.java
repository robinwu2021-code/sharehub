package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyBlacklistVO;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogStats;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyLogVO;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyTemplateVO;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendReq;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.SendResult;
import ai.neargo.sharehub.platform.notify.entity.NotifyBlacklist;
import ai.neargo.sharehub.platform.notify.entity.NotifyTemplate;
import ai.neargo.sharehub.platform.notify.service.NotifyBlacklistService;
import ai.neargo.sharehub.platform.notify.service.NotifyLogService;
import ai.neargo.sharehub.platform.notify.service.NotifySendService;
import ai.neargo.sharehub.platform.notify.service.NotifyTemplateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统设置 · 消息触达（[api/README §7.3]）：通知模板 · 发送记录 · 触达拉黑，
 * 外加域间发送入口 {@code POST /internal/platform/notify/send}。
 *
 * <p><b>为什么没有类级 {@code @RequestMapping}</b>：本控制器同时承载 {@code /api/platform/**}
 * （运营端，Bearer + RBAC）与 {@code /internal/**}（域间调用，内网 + 受信头，**无 {@code @PreAuthorize}**）
 * 两个前缀。它们服务的是同一批业务规则（发送必须过黑名单），拆成两个类反而会让"唯一正门"的
 * 约束散开，因此保留在一起、用完整路径区分。
 *
 * <p>解除拉黑用动作端点 {@code /release} 而非 DELETE —— 它是软删（留痕），语义上是状态流转。
 */
@RestController
public class NotifyController {

    private final NotifyTemplateService templateService;
    private final NotifyLogService logService;
    private final NotifyBlacklistService blacklistService;
    private final NotifySendService sendService;

    public NotifyController(NotifyTemplateService templateService, NotifyLogService logService,
                            NotifyBlacklistService blacklistService, NotifySendService sendService) {
        this.templateService = templateService;
        this.logService = logService;
        this.blacklistService = blacklistService;
        this.sendService = sendService;
    }

    // —— 通知模板（菜单叶：系统设置 › 消息触达 › 通知模板）——

    @GetMapping("/api/platform/notify-templates")
    @PreAuthorize("@perm.can('system:notify_template:read')")
    public PageResult<NotifyTemplateVO> templates(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String channel,
                                                  @RequestParam(required = false) String lang,
                                                  @RequestParam(required = false) String scene,
                                                  @RequestParam(required = false) String status) {
        return templateService.page(page, size, keyword, Map.of(
                "channel", nz(channel), "lang", nz(lang), "scene", nz(scene), "status", nz(status)));
    }

    @PostMapping("/api/platform/notify-templates")
    @PreAuthorize("@perm.can('system:notify_template:update')")
    public NotifyTemplateVO createTemplate(@RequestBody NotifyTemplate body) {
        return templateService.save(body);
    }

    @PostMapping("/api/platform/notify-templates/{templateNo}")
    @PreAuthorize("@perm.can('system:notify_template:update')")
    public NotifyTemplateVO updateTemplate(@PathVariable String templateNo, @RequestBody NotifyTemplate body) {
        body.setTemplateNo(templateNo); // 路径为准
        return templateService.save(body);
    }

    // —— 发送记录（append，只读）——

    /** {@code sort} 受控：仅 {@code sentAt|cost}，其它值由 service 拒绝（防注入）。 */
    @GetMapping("/api/platform/notify-logs")
    @PreAuthorize("@perm.can('system:notify_log:read')")
    public PageResult<NotifyLogVO> notifyLogs(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String channel,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String scene,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) String dir) {
        return logService.page(page, size, keyword, channel, status, scene, sort, dir);
    }

    /** 页头统计（全量口径，非当前分页合计）。 */
    @GetMapping("/api/platform/notify-logs/stats")
    @PreAuthorize("@perm.can('system:notify_log:read')")
    public NotifyLogStats notifyLogStats() {
        return logService.stats();
    }

    // —— 触达拉黑 ——

    @GetMapping("/api/platform/notify-blacklist")
    @PreAuthorize("@perm.can('system:notify_blacklist:read')")
    public PageResult<NotifyBlacklistVO> blacklist(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String channel,
                                                   @RequestParam(required = false) String reason,
                                                   @RequestParam(required = false) String status) {
        return blacklistService.page(page, size, keyword, channel, reason, status);
    }

    @PostMapping("/api/platform/notify-blacklist")
    @PreAuthorize("@perm.can('system:notify_blacklist:update')")
    public NotifyBlacklistVO block(@RequestBody NotifyBlacklist body) {
        if (body.getBlockedBy() == null || body.getBlockedBy().isBlank()) {
            body.setBlockedBy(currentOperator()); // 操作人以会话为准，不信前端传值
        }
        return blacklistService.block(body);
    }

    /** 解除（软删：{@code status=RELEASED} + 留痕，不物理删）。 */
    @PostMapping("/api/platform/notify-blacklist/{blockNo}/release")
    @PreAuthorize("@perm.can('system:notify_blacklist:update')")
    public NotifyBlacklistVO release(@PathVariable String blockNo) {
        return blacklistService.release(blockNo, currentOperator());
    }

    // —— 域间：发送通知（无 @PreAuthorize，内网 + 受信头；发前查黑名单）——

    @PostMapping("/internal/platform/notify/send")
    public SendResult send(@RequestBody SendReq body) {
        return sendService.send(body);
    }

    /** 当前操作人；无会话（域间/定时任务）时记 SYSTEM。 */
    private static String currentOperator() {
        return SecurityUtils.currentUser().map(u -> u.userNo()).orElse("SYSTEM");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 模板渲染预览。缺失的变量原样保留并单列，不静默替空串 —— 否则漏配上线才发现。 */
    @PostMapping("/api/platform/notify-templates/{no}/preview")
    @PreAuthorize("@perm.can('system:notify_template:read')")
    public Object previewTemplate(@PathVariable String no, @RequestBody(required = false) java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> vars = body == null ? java.util.Map.of()
                : (java.util.Map<String, String>) body.getOrDefault("vars", java.util.Map.of());
        return templateService.preview(no, vars);
    }

    /** 模板试发。**必带幂等键** —— 试发也是真发真扣钱，双击不该发两条。 */
    @PostMapping("/api/platform/notify-templates/{no}/test-send")
    @PreAuthorize("@perm.can('system:notify_template:update')")
    public Object testSendTemplate(@PathVariable String no,
                                   @RequestBody NotifyDtos.NotifyTestSendReq req) {
        return templateService.testSend(no, req);
    }

    /**
     * 重发一条发送记录。**新增一条，原记录一字不改**（审计要看得见发了两次）。
     * 目标/渠道/模板沿用原记录，只接受幂等键。
     */
    @PostMapping("/api/platform/notify-logs/{no}/resend")
    @PreAuthorize("@perm.can('system:notify_log:update')")
    public Object resendLog(@PathVariable String no, @RequestBody NotifyDtos.NotifyResendReq req) {
        return logService.resend(no, req == null ? null : req.idempotencyKey());
    }
}
