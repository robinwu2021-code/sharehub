package ai.neargo.sharehub.agent.service.impl;

import ai.neargo.sharehub.agent.entity.AgtAgent;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.agent.service.AgentService;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.common.core.IdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理商业务实现。数据过滤由 DataScopeInterceptor 横切（Service 不写 where）；
 * 功能权限在 Controller 层 @PreAuthorize（Service 不判权限）。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private final AgentMapper mapper;

    public AgentServiceImpl(AgentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<Agent> page(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<AgtAgent> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(AgtAgent::getName, keyword).or().like(AgtAgent::getAgentNo, keyword)
                    .or().like(AgtAgent::getRegionScope, keyword));
        }
        w.orderByAsc(AgtAgent::getId);
        Page<AgtAgent> r = mapper.selectPage(new Page<>(p, s), w);
        List<Agent> rows = r.getRecords().stream().map(AgentServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    public Agent save(String agentNo, Agent in) {
        String no = (agentNo != null && !agentNo.isBlank()) ? agentNo
                : (in.agentNo() != null && !in.agentNo().isBlank()) ? in.agentNo()
                : IdGenerator.next("AG");   // 业务键复用 neargo-common-core（物理主键仍库内自增 Long）
        AgtAgent e = mapper.selectOne(new LambdaQueryWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, no));
        boolean insert = (e == null);
        if (insert) { e = new AgtAgent(); e.setAgentNo(no); e.setTenantId("MAIN"); }
        e.setName(in.name());
        e.setContact(in.contact());
        e.setRegionScope(in.regionScope());
        e.setShareRate(in.shareRate());
        e.setCabinetCount(in.cabinetCount());
        e.setStatus(in.status() == null ? "ENABLED" : in.status());
        if (insert) mapper.insert(e); else mapper.updateById(e);
        return toVO(e);
    }

    private static Agent toVO(AgtAgent e) {
        return new Agent(e.getAgentNo(), e.getName(), e.getContact(), e.getRegionScope(),
                e.getShareRate() == null ? 0 : e.getShareRate(),
                e.getCabinetCount() == null ? 0 : e.getCabinetCount(), e.getStatus());
    }

    // ── 归档 / 取消归档（前端契约 Archivable）──
    // 本实现不走 AbstractCrudService（它有自己的业务规则），故在此实现同样语义：
    // archivedAt 时间戳，null=在用。**与 BaseEntity.deleted 是两回事**，见 Archivable。

    @Override
    @Transactional
    public Agent archive(String no) {
        return setArchived(no, java.time.LocalDateTime.now());
    }

    @Override
    @Transactional
    public Agent unarchive(String no) {
        return setArchived(no, null);
    }

    private Agent setArchived(String no, java.time.LocalDateTime at) {
        AgtAgent e = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, no).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("代理商不存在: " + no);
        e.setArchivedAt(at);
        mapper.updateById(e);
        return toVO(e);
    }
}
