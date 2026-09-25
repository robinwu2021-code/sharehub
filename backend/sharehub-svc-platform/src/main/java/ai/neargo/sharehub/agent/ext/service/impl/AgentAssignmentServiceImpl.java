package ai.neargo.sharehub.agent.ext.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.entity.AgtAgent;
import ai.neargo.sharehub.agent.ext.AgentOwnershipSync;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignReq;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignableAsset;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignmentLog;
import ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.ReclaimReq;
import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import ai.neargo.sharehub.agent.ext.entity.AgtAssignment;
import ai.neargo.sharehub.agent.ext.mapper.AgtAssignmentMapper;
import ai.neargo.sharehub.agent.ext.service.AgentAssignmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 划拨实现。 */
@Service
public class AgentAssignmentServiceImpl implements AgentAssignmentService {

    private static final Set<String> TARGET_TYPES = Set.of("CABINET", "LOCATION", "SITE");
    private static final Set<String> ACTIONS = Set.of("ASSIGN", "REVOKE");
    private static final String TENANT_MAIN = "MAIN";

    /**
     * {@code assign_no} 的前缀。
     *
     * <p>⚠️ [db-design §1.4.1] 的前缀注册表**没有登记 {@code assign_no}**（已在交付报告列为缺漏）。
     * 这里取 {@code ASG} 而非 {@code AS} —— {@code AS} 已分配给广告位
     * （{@link ai.neargo.sharehub.common.BizKey#AD_SLOT}），撞了改不回来。
     * 注册表补登后请把本常量迁进 {@code BizKey}。
     */
    private static final String ASSIGN_PREFIX = "ASG";

    private final AgtAssignmentMapper mapper;
    private final AgentOwnershipSync ownershipSync;
    private final AgentMapper agentMapper;
    private final CabinetQueryPort cabinetQuery;
    private final LocMappers.SiteMapper siteMapper;

    public AgentAssignmentServiceImpl(AgtAssignmentMapper mapper, AgentOwnershipSync ownershipSync,
                                      AgentMapper agentMapper, CabinetQueryPort cabinetQuery,
                                      LocMappers.SiteMapper siteMapper) {
        this.mapper = mapper;
        this.ownershipSync = ownershipSync;
        this.agentMapper = agentMapper;
        this.cabinetQuery = cabinetQuery;
        this.siteMapper = siteMapper;
    }

    @Override
    public PageResult<AssignmentLog> page(Integer page, Integer size, String agentNo,
                                          String targetType, String targetNo) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<AgtAssignment> w = new LambdaQueryWrapper<>();
        if (agentNo != null && !agentNo.isBlank()) w.eq(AgtAssignment::getAgentNo, agentNo);
        if (targetType != null && !targetType.isBlank()) w.eq(AgtAssignment::getTargetType, targetType);
        if (targetNo != null && !targetNo.isBlank()) w.eq(AgtAssignment::getTargetNo, targetNo);
        w.orderByDesc(AgtAssignment::getId);

        Page<AgtAssignment> r = mapper.selectPage(new Page<>(p, s), w);
        List<AssignmentLog> rows = r.getRecords().stream().map(AgentAssignmentServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    /**
     * 划拨：落流水 + <b>同事务回写归属冗余列</b>。
     *
     * <p>两者必须同事务 —— 只落流水不回写，代理登录后看不到划给他的资产（数据范围过滤的是冗余列）；
     * 只回写不落流水，则归属变更无从追溯。任一失败必须整体回滚。
     */
    @Override
    @Transactional
    public AssignmentLog assign(AssignReq req) {
        if (req == null) throw new IllegalArgumentException("划拨入参必填");
        if (req.agentNo() == null || req.agentNo().isBlank()) throw BizException.badRequest("error.common.missing_parameter", "agentNo");
        if (req.targetNo() == null || req.targetNo().isBlank()) throw BizException.badRequest("error.common.missing_parameter", "targetNo");
        if (!TARGET_TYPES.contains(req.targetType())) {
            throw new IllegalArgumentException("划拨对象类型非法: " + req.targetType());
        }
        if (!ACTIONS.contains(req.action())) {
            throw new IllegalArgumentException("划拨动作非法: " + req.action());
        }
        // 代理必须存在且启用 —— 划给不存在的 agentNo 会让资产变孤儿：
        // 冗余列写进去了，但没有任何账号的数据范围能匹配到它，资产从两边都消失。
        AgtAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, req.agentNo()).last("limit 1"));
        if (agent == null) throw BizException.notFound(req.agentNo());
        if ("ASSIGN".equals(req.action()) && !"ENABLED".equals(agent.getStatus())) {
            throw new IllegalArgumentException("代理商已停用，不可划入资产: " + req.agentNo());
        }

        AgtAssignment e = new AgtAssignment();
        e.setAssignNo(IdGenerator.next(ASSIGN_PREFIX));
        e.setTenantId(TENANT_MAIN);
        e.setAgentNo(req.agentNo());
        e.setTargetType(req.targetType());
        e.setTargetNo(req.targetNo());
        e.setAction(req.action());
        e.setOperator(req.operator());
        e.setCreatedAt(LocalDateTime.now().toString());
        mapper.insert(e);

        // 回写归属冗余列：ASSIGN 写入 agentNo，REVOKE 置空（转平台直营）。
        // 级联规则与「为何不回写 ord_order/wo_order」见 AgentOwnershipSync 类注释。
        String target = "ASSIGN".equals(req.action()) ? req.agentNo() : null;
        ownershipSync.apply(req.targetType(), req.targetNo(), target);

        return toVO(e);
    }

    /** 可划拨资产候选池：机柜经 Port 取（core 的表），站点读 platform 自己的 {@code loc_site}。 */
    @Override
    public List<AssignableAsset> assignable(String keyword, String agentNo, String assetType, Integer limit) {
        int n = (limit == null || limit < 1) ? 100 : Math.min(limit, 500);
        List<AssignableAsset> out = new java.util.ArrayList<>();

        if (assetType == null || "CABINET".equals(assetType)) {
            for (CabinetBrief c : cabinetQuery.assignable(keyword, agentNo, n)) {
                out.add(new AssignableAsset("CABINET", c.cabinetNo(), c.name(),
                        c.agentNo(), agentName(c.agentNo())));
            }
        }
        if (assetType == null || "SITE".equals(assetType)) {
            LambdaQueryWrapper<LocSite> w = new LambdaQueryWrapper<LocSite>().isNull(LocSite::getArchivedAt);
            if (keyword != null && !keyword.isBlank()) {
                w.and(q -> q.like(LocSite::getName, keyword).or().like(LocSite::getSiteNo, keyword));
            }
            if (agentNo != null && !agentNo.isBlank()) {
                if (CabinetQueryPort.DIRECT_OPERATED.equals(agentNo)) w.isNull(LocSite::getAgentNo);
                else w.eq(LocSite::getAgentNo, agentNo);
            }
            w.orderByAsc(LocSite::getSiteNo).last("limit " + n);
            for (LocSite s : siteMapper.selectList(w)) {
                out.add(new AssignableAsset("SITE", s.getSiteNo(), s.getName(),
                        s.getAgentNo(), agentName(s.getAgentNo())));
            }
        }
        return out;
    }

    /**
     * 批量回收到平台直营。
     *
     * <p><b>整批一个事务</b>：与批量导入同样的理由 —— 部分成功后运营无从判断该重提全部还是补提剩余，
     * 而「已回收的再回收一次」虽然幂等、却会多出一行看不懂的流水。
     *
     * <p><b>归属从现状反查</b>，入参不带 {@code agentNo}（见 {@link ReclaimReq} 注释）。
     * 找不到的资产直接抛错 —— 静默跳过会让「编号打错」表现为「回收成功但没生效」。
     * 已是直营的资产不报错也不落流水：这样重试整批是安全的。
     */
    @Override
    @Transactional
    public List<AssignmentLog> reclaim(ReclaimReq req) {
        if (req == null) throw new IllegalArgumentException("回收入参必填");
        List<String> cabinetNos = req.cabinetNos() == null ? List.of() : req.cabinetNos();
        List<String> siteNos = req.siteNos() == null ? List.of() : req.siteNos();
        if (cabinetNos.isEmpty() && siteNos.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个待回收资产");
        }

        List<AssignmentLog> logs = new java.util.ArrayList<>();

        // 机柜：一次批量反查归属，避免 N+1
        java.util.Map<String, String> owners = new java.util.HashMap<>();
        for (CabinetBrief c : cabinetQuery.briefsByNos(cabinetNos)) owners.put(c.cabinetNo(), c.agentNo());
        for (String no : cabinetNos) {
            if (!owners.containsKey(no)) throw BizException.notFound(no);
            String owner = owners.get(no);
            if (owner == null || owner.isBlank()) continue;   // 已是直营，重试友好
            logs.add(assign(new AssignReq(owner, "CABINET", no, "REVOKE", req.operatorName())));
        }

        for (String no : siteNos) {
            LocSite s = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>()
                    .eq(LocSite::getSiteNo, no).last("limit 1"));
            if (s == null) throw BizException.notFound(no);
            if (s.getAgentNo() == null || s.getAgentNo().isBlank()) continue;
            logs.add(assign(new AssignReq(s.getAgentNo(), "SITE", no, "REVOKE", req.operatorName())));
        }
        return logs;
    }

    /** 代理名，查不到返回 null（不抛错 —— 候选池是只读展示，不该因为脏数据整页打不开）。 */
    private String agentName(String agentNo) {
        if (agentNo == null || agentNo.isBlank()) return null;
        AgtAgent a = agentMapper.selectOne(new LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        return a == null ? null : a.getName();
    }

    private static AssignmentLog toVO(AgtAssignment e) {
        return new AssignmentLog(e.getAssignNo(), e.getAgentNo(), e.getTargetType(), e.getTargetNo(),
                e.getAction(), e.getOperator(), e.getCreatedAt());
    }
}
