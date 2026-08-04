package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.ContractAttachment;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.LeadFollowUp;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.LeadFollowUpReq;

import java.util.List;

/** 线索跟进与合同附件。 */
public interface LeadFollowService {

    PageResult<LeadFollowUp> pageFollowUps(String leadNo, Integer page, Integer size);

    /**
     * 记一条跟进，**可同时推进线索阶段**。
     *
     * <p>这是它与普通流水的区别：跟进本身就是推进的动作。分成「记跟进」+「改阶段」
     * 两个接口的话，两者会不一致 —— 有跟进记录但阶段没动，或阶段跳了却查不到是谁推的。
     */
    LeadFollowUp addFollowUp(String leadNo, LeadFollowUpReq req);

    List<ContractAttachment> attachments(String contractNo);

    /** 添加附件元数据。**不接收字节流** —— 见 {@code LocContractAttach} 类注释。 */
    ContractAttachment addAttachment(String contractNo, String fileName, Long size);

    /** 移除附件。软删（BaseEntity.deleted），元数据保留可追溯。 */
    boolean removeAttachment(String contractNo, String attachNo);
}
