package ai.neargo.sharehub.loc.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractLogItem;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractSummary;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractTickResult;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 进场合同（2026-09-25 定：合同走审批；TDD-运营核心流程/02）。
 *
 * <p>状态只经动作接口改：DRAFT →提交→ PENDING →审批→ SIGNED →签署归档 + 到生效日→ ACTIVE →到期 / 终止。
 * 同一站点任一时刻最多一份 ACTIVE：由「激活时先把旧的 EXPIRE」与「提交 / 审批时的期限重叠校验」共同保证。
 */
public interface ContractService {

    record Draft(String venueNo, String siteNo, String shareMode, String shareBase, BigDecimal shareRate,
                 BigDecimal guaranteeAmount, BigDecimal entryFee, String currency, String settlePeriod,
                 LocalDate startAt, LocalDate endAt, boolean autoRenew, boolean exclusive, Integer deviceQuota,
                 String placementNote, BigDecimal depositAmount, String depositTerms, String signerName, String remark) {
    }

    record Query(Integer page, Integer size, String keyword, String status, String venueNo, String siteNo,
                 LocalDate endFrom, LocalDate endTo, Boolean pendingMine) {
    }

    PageResult<Contract> page(Query q);

    ContractSummary summary();

    Contract get(String contractNo);

    List<ContractLogItem> logs(String contractNo);

    Contract create(Draft d);

    /** 商机签约转化（B8）：带谈判条款建草稿，记来源商机（BD 归因）。 */
    Contract createFromLead(Draft d, String leadNo);

    Contract updateDraft(String contractNo, Draft d);

    Contract submit(String contractNo);

    Contract withdraw(String contractNo, String note);

    /** result = APPROVE | REJECT；REJECT 时 reason 必填；不能审批自己提交的合同。 */
    Contract audit(String contractNo, String result, String reason);

    /** 在 SIGNED 上补签署件与日期；若生效日已到，同事务内生效。 */
    Contract sign(String contractNo, LocalDate signedAt, List<String> fileNos);

    /**
     * 财务会签（条件加签，V104）：运营通过后分成比例 / 进场费 / 保底超阈值的合同停在 PENDING（audit_stage=FINANCE）。
     * result = APPROVE | REJECT；不能会签自己提交的、也不能与运营审批人是同一人。
     */
    Contract cosign(String contractNo, String result, String reason);

    /**
     * 提前终止申请（裁决 #4：终止要审批）。合同照常生效，不影响营业；
     * 获批后终止日已到则立即终止，否则到日由定时任务终止。
     */
    Contract terminate(String contractNo, String reason, LocalDate effectiveAt);

    /** 审批终止申请：result = APPROVE | REJECT；不能审批自己发起的；驳回原因必填。 */
    Contract auditTermination(String contractNo, String result, String reason);

    /** 生效中合同改条款 = 建补充协议草稿（关联原合同）；生效日起取代原合同，原版保留。 */
    Contract supplement(String contractNo, LocalDate startAt);

    /** 以原合同为模板建续签草稿（开始日 = 原到期日次日，期限等长）。 */
    Contract renew(String contractNo);

    Contract attach(String contractNo, List<String> fileNos);

    Contract removeAttachment(String contractNo, String attachNo);

    /** 定时：终止（已批且到终止日）、到期、生效。每份合同独立事务，一份失败不影响其他。 */
    ContractTickResult tick(LocalDate today);

    LocalDate today();
}
