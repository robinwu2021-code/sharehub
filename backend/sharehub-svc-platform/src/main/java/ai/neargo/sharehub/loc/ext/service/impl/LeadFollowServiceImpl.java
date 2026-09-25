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
import ai.neargo.sharehub.loc.ext.LeadStateMachine;
import ai.neargo.sharehub.loc.ext.LeadStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
    private final LeadStateMachine sm;

    public LeadFollowServiceImpl(LocLeadFollowMapper followMapper,
                                 LocContractAttachMapper attachMapper,
                                 LocLeadMapper leadMapper, LeadStateMachine sm) {
        this.followMapper = followMapper;
        this.attachMapper = attachMapper;
        this.leadMapper = leadMapper;
        this.sm = sm;
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
            throw BizException.badRequest("error.common.missing_parameter", "content");
        }
        LocLead lead = leadMapper.selectOne(new LambdaQueryWrapper<LocLead>()
                .eq(LocLead::getLeadNo, leadNo).last("limit 1"));
        if (lead == null) throw BizException.notFound(leadNo);
        // 池里的商机没有负责人：先认领再跟进，否则「谁在跟」又说不清了
        if (lead.getInPool() != null && lead.getInPool() == 1) throw BizException.conflict("error.lead.in_pool", leadNo);

        String from = lead.getStage();
        String to = (req.toStage() == null || req.toStage().isBlank()) ? from : req.toStage();
        String event = sm.check(from, to);

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
        // 或「阶段跳了却查不到是谁推的」。条件更新按原阶段：并发两人推同一条，只一人赢。
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<LocLead> u = new LambdaUpdateWrapper<LocLead>().eq(LocLead::getId, lead.getId())
                .eq(LocLead::getStage, from)
                .set(LocLead::getLastFollowAt, now).set(LocLead::getRemindAt, null);   // 跟进即重新计时
        if (e.getNextAt() != null) u.set(LocLead::getNextFollowAt, e.getNextAt().toString());
        if (event != null) {
            u.set(LocLead::getStage, to);
            if (LeadStatus.LOST.name().equals(to)) {
                // 跟进内容就是丢单原因（必填已由上面的 content 校验保证）
                u.set(LocLead::getLostReason, e.getContent()).set(LocLead::getLostAt, now);
            }
            if (LeadStatus.NEW.name().equals(to) && LeadStatus.LOST.name().equals(from)) u.set(LocLead::getReactivatedAt, now);
        }
        if (leadMapper.update(null, u) == 0) throw BizException.conflict("error.common.state_changed");
        return toVO(e);
    }

    @Override
    public List<ContractAttachment> attachments(String contractNo) {
        return attachMapper.selectList(new LambdaQueryWrapper<LocContractAttach>()
                        .eq(LocContractAttach::getContractNo, contractNo)
                        .orderByDesc(LocContractAttach::getId))
                .stream().map(LeadFollowServiceImpl::toVO).toList();
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
