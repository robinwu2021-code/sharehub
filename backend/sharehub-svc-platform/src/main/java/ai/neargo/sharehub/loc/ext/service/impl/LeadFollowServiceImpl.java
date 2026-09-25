package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.ContractAttachment;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.LeadFollowUp;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos2.LeadFollowUpReq;
import ai.neargo.sharehub.loc.ext.entity.LocContractAttach;
import ai.neargo.sharehub.loc.ext.entity.LocLeadFollow;
import ai.neargo.sharehub.loc.ext.mapper.LocContractAttachMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadFollowMapper;
import ai.neargo.sharehub.loc.ext.service.LeadFollowService;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 线索跟进与合同附件实现。 */
@Service
public class LeadFollowServiceImpl implements LeadFollowService {

    private static final String TENANT_MAIN = "MAIN";

    private final LocLeadFollowMapper followMapper;
    private final LocContractAttachMapper attachMapper;
    private final LocLeadMapper leadMapper;

    public LeadFollowServiceImpl(LocLeadFollowMapper followMapper,
                                 LocContractAttachMapper attachMapper,
                                 LocLeadMapper leadMapper) {
        this.followMapper = followMapper;
        this.attachMapper = attachMapper;
        this.leadMapper = leadMapper;
    }

    @Override
    public PageResult<LeadFollowUp> pageFollowUps(String leadNo, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 200);
        LambdaQueryWrapper<LocLeadFollow> w = new LambdaQueryWrapper<LocLeadFollow>()
                .eq(LocLeadFollow::getLeadNo, leadNo)
                .orderByDesc(LocLeadFollow::getId);
        Page<LocLeadFollow> r = followMapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream()
                .map(LeadFollowServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    @Transactional
    public LeadFollowUp addFollowUp(String leadNo, LeadFollowUpReq req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new IllegalArgumentException("跟进内容必填");
        }
        LocLead lead = leadMapper.selectOne(new LambdaQueryWrapper<LocLead>()
                .eq(LocLead::getLeadNo, leadNo).last("limit 1"));
        if (lead == null) throw BizException.notFound(leadNo);

        String from = lead.getStage();
        String to = (req.toStage() == null || req.toStage().isBlank()) ? from : req.toStage();

        LocLeadFollow e = new LocLeadFollow();
        e.setFollowNo("LF" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        e.setTenantId(lead.getTenantId() == null ? TENANT_MAIN : lead.getTenantId());
        e.setLeadNo(leadNo);
        e.setChannel(req.channel());
        e.setFromStage(from);
        e.setToStage(to);
        e.setOwner(SecurityUtils.currentUser().map(LoginUser::username).orElse(null));
        e.setContent(req.content().trim());
        if (req.nextAt() != null && !req.nextAt().isBlank()) {
            e.setNextAt(LocalDate.parse(req.nextAt().substring(0, 10)));
        }
        e.setCreatedAt(LocalDateTime.now());
        followMapper.insert(e);

        // 跟进与推进阶段**同事务** —— 分成两个接口的话，会出现「有跟进记录但阶段没动」
        // 或「阶段跳了却查不到是谁推的」。
        if (!to.equals(from)) {
            lead.setStage(to);
            leadMapper.updateById(lead);
        }
        return toVO(e);
    }

    @Override
    public List<ContractAttachment> attachments(String contractNo) {
        return attachMapper.selectList(new LambdaQueryWrapper<LocContractAttach>()
                        .eq(LocContractAttach::getContractNo, contractNo)
                        .orderByDesc(LocContractAttach::getId))
                .stream().map(LeadFollowServiceImpl::toVO).toList();
    }

    @Override
    @Transactional
    public ContractAttachment addAttachment(String contractNo, String fileName, Long size) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名必填");
        }
        LocContractAttach e = new LocContractAttach();
        e.setAttachNo("ATT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        e.setTenantId(TENANT_MAIN);
        e.setContractNo(contractNo);
        e.setFileName(fileName);
        e.setSize(size == null ? 0L : size);
        e.setUploadedBy(SecurityUtils.currentUser().map(LoginUser::username).orElse(null));
        e.setUploadedAt(LocalDateTime.now());
        attachMapper.insert(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public boolean removeAttachment(String contractNo, String attachNo) {
        LocContractAttach e = attachMapper.selectOne(new LambdaQueryWrapper<LocContractAttach>()
                .eq(LocContractAttach::getContractNo, contractNo)
                .eq(LocContractAttach::getAttachNo, attachNo).last("limit 1"));
        if (e == null) return false;
        // 软删：元数据保留可追溯 —— 「这份合同曾经有过一个附件后来被删了」本身是信息。
        return attachMapper.deleteById(e.getId()) > 0;
    }

    private static LeadFollowUp toVO(LocLeadFollow e) {
        return new LeadFollowUp(e.getFollowNo(), e.getLeadNo(), e.getChannel(),
                e.getFromStage(), e.getToStage(), e.getOwner(), e.getContent(),
                e.getNextAt() == null ? null : e.getNextAt().toString(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    private static ContractAttachment toVO(LocContractAttach e) {
        return new ContractAttachment(e.getAttachNo(), e.getFileName(), e.getSize(),
                e.getUploadedBy(), e.getUploadedAt() == null ? null : e.getUploadedAt().toString());
    }
}
