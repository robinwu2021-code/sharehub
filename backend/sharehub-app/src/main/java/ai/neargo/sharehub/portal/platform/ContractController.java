package ai.neargo.sharehub.portal.platform;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.dto.ContractDtos.AttachReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.AuditReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractLogItem;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractSummary;
import ai.neargo.sharehub.loc.dto.ContractDtos.NoteReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.SignReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.SupplementReq;
import ai.neargo.sharehub.loc.dto.ContractDtos.TerminateReq;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.service.ContractService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 进场合同（2026-09-25 定：合同走审批；接口设计 §3.1）。从 OpsController / LocExtController 拆出。
 *
 * <p>状态只经动作端点改：submit · withdraw · audit · cosign · sign · terminate（申请）· termination/audit；编辑只接受草稿。
 * 审批统一一个 {@code /audit}（api/README §1.5）：驳回原因必填，审批人由服务端回填，不能审批自己提交的。
 */
@RestController
@RequestMapping("/api/ops/contracts")
public class ContractController {

    private final ContractService contracts;

    public ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @PreAuthorize("@perm.can('location:contract:read')")
    public PageResult<Contract> page(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String venueNo,
                                     @RequestParam(required = false) String siteNo,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endFrom,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endTo,
                                     @RequestParam(required = false) Boolean pendingMine) {
        return contracts.page(new ContractService.Query(page, size, keyword, status, venueNo, siteNo, endFrom, endTo, pendingMine));
    }

    @GetMapping("/summary")
    @PreAuthorize("@perm.can('location:contract:read')")
    public ContractSummary summary() {
        return contracts.summary();
    }

    @GetMapping("/{contractNo}")
    @PreAuthorize("@perm.can('location:contract:read')")
    public Contract get(@PathVariable String contractNo) {
        return contracts.get(contractNo);
    }

    @GetMapping("/{contractNo}/logs")
    @PreAuthorize("@perm.can('location:contract:read')")
    public List<ContractLogItem> logs(@PathVariable String contractNo) {
        return contracts.logs(contractNo);
    }

    @PostMapping
    @PreAuthorize("@perm.can('location:contract:create')")
    public Contract create(@RequestBody ContractReq r) {
        return contracts.create(draft(r));
    }

    @PostMapping("/{contractNo}")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Contract update(@PathVariable String contractNo, @RequestBody ContractReq r) {
        return contracts.updateDraft(contractNo, draft(r));
    }

    @PostMapping("/{contractNo}/submit")
    @PreAuthorize("@perm.can('location:contract:submit')")
    public Contract submit(@PathVariable String contractNo) {
        return contracts.submit(contractNo);
    }

    @PostMapping("/{contractNo}/withdraw")
    @PreAuthorize("@perm.can('location:contract:submit')")
    public Contract withdraw(@PathVariable String contractNo, @RequestBody(required = false) NoteReq r) {
        return contracts.withdraw(contractNo, r == null ? null : r.note());
    }

    @PostMapping("/{contractNo}/audit")
    @PreAuthorize("@perm.can('location:contract:audit')")
    public Contract audit(@PathVariable String contractNo, @RequestBody AuditReq r) {
        return contracts.audit(contractNo, r.result(), r.reason());
    }

    @PostMapping("/{contractNo}/sign")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Contract sign(@PathVariable String contractNo, @RequestBody SignReq r) {
        return contracts.sign(contractNo, r.signedAt(), r.fileNos());
    }

    /** 财务会签（条件加签）：运营通过后超阈值的合同停在 PENDING（auditStage=FINANCE）。 */
    @PostMapping("/{contractNo}/cosign")
    @PreAuthorize("@perm.can('location:contract:cosign')")
    public Contract cosign(@PathVariable String contractNo, @RequestBody AuditReq r) {
        return contracts.cosign(contractNo, r.result(), r.reason());
    }

    /** 发起提前终止申请（裁决 #4）：审批期间合同照常生效。 */
    @PostMapping("/{contractNo}/terminate")
    @PreAuthorize("@perm.can('location:contract:terminate')")
    public Contract terminate(@PathVariable String contractNo, @RequestBody TerminateReq r) {
        return contracts.terminate(contractNo, r.reason(), r.effectiveAt());
    }

    @PostMapping("/{contractNo}/termination/audit")
    @PreAuthorize("@perm.can('location:contract:audit')")
    public Contract auditTermination(@PathVariable String contractNo, @RequestBody AuditReq r) {
        return contracts.auditTermination(contractNo, r.result(), r.reason());
    }

    /** 生效中合同改条款：生成补充协议草稿（关联原合同），走同样的提交 → 审批 → 签署。 */
    @PostMapping("/{contractNo}/supplement")
    @PreAuthorize("@perm.can('location:contract:create')")
    public Contract supplement(@PathVariable String contractNo, @RequestBody(required = false) SupplementReq r) {
        return contracts.supplement(contractNo, r == null ? null : r.startAt());
    }

    @PostMapping("/{contractNo}/renew")
    @PreAuthorize("@perm.can('location:contract:create')")
    public Contract renew(@PathVariable String contractNo) {
        return contracts.renew(contractNo);
    }

    @PostMapping("/{contractNo}/attachments")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Contract attach(@PathVariable String contractNo, @RequestBody AttachReq r) {
        return contracts.attach(contractNo, r.fileNos());
    }

    @PostMapping("/{contractNo}/attachments/{attachNo}/remove")
    @PreAuthorize("@perm.can('location:contract:update')")
    public Contract removeAttachment(@PathVariable String contractNo, @PathVariable String attachNo) {
        return contracts.removeAttachment(contractNo, attachNo);
    }

    private static ContractService.Draft draft(ContractReq r) {
        return new ContractService.Draft(r.venueNo(), r.siteNo(), r.shareMode(), r.shareBase(), r.shareRate(),
                r.guaranteeAmount(), r.entryFee(), r.currency(), r.settlePeriod(), r.startAt(), r.endAt(),
                Boolean.TRUE.equals(r.autoRenew()), Boolean.TRUE.equals(r.exclusive()), r.deviceQuota(),
                r.placementNote(), r.depositAmount(), r.depositTerms(), r.signerName(), r.remark());
    }
}
