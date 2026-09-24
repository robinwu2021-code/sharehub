package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.auth.PermVersion;
import ai.neargo.sharehub.platform.iam.entity.IamEntities.IamDataScope;
import ai.neargo.sharehub.platform.iam.mapper.IamMappers.DataScopeMapper;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.DataScopeEntry;
import ai.neargo.sharehub.platform.org.service.DataScopeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 数据权限落库实现（G7 缺口的对端）。
 *
 * <p>实体与 Mapper 复用 {@code platform/iam} 已有的 {@code IamDataScope}/{@code DataScopeMapper}
 * —— 数据范围是权限域的资产，这里只提供「组织页面保存它」的写入口，不另立一套表。
 *
 * <p>保存后 {@link PermVersion#bump()}：在线员工的下一次请求就会重算范围，
 * 否则管理员改完范围要等缓存自然过期，看起来像"保存没生效"。
 */
@Service
public class DataScopeServiceImpl implements DataScopeService {

    private static final Set<String> SUBJECT_TYPES = Set.of("ROLE", "EMPLOYEE");
    private static final Set<String> SCOPE_TYPES =
            Set.of("ALL", "REGION", "SITE", "LOCATION", "VENUE", "AGENT", "SELF");
    /** 这两种范围语义上不需要附加值，落库前清空，避免残留旧 refs 造成"看似有范围"的误解。 */
    private static final Set<String> NO_REF_SCOPES = Set.of("ALL", "SELF");

    private final DataScopeMapper mapper;
    private final PermVersion permVersion;

    public DataScopeServiceImpl(DataScopeMapper mapper, PermVersion permVersion) {
        this.mapper = mapper;
        this.permVersion = permVersion;
    }

    @Override
    public DataScopeEntry get(String subjectType, String subjectNo) {
        IamDataScope e = selectOne(subjectType, subjectNo);
        return e == null ? null : toVO(e);
    }

    @Override
    public DataScopeEntry save(String subjectType, String subjectNo, String scopeType, String scopeRefs) {
        if (!SUBJECT_TYPES.contains(subjectType)) {
            throw new IllegalArgumentException("非法 subjectType: " + subjectType);
        }
        if (scopeType == null || !SCOPE_TYPES.contains(scopeType)) {
            throw new IllegalArgumentException("非法 scopeType: " + scopeType);
        }
        String refs = NO_REF_SCOPES.contains(scopeType) ? null : scopeRefs;

        IamDataScope current = selectOne(subjectType, subjectNo);
        if (current == null) {
            IamDataScope e = new IamDataScope();
            e.setSubjectType(subjectType);
            e.setSubjectNo(subjectNo);
            e.setScopeType(scopeType);
            e.setScopeRefs(refs);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(e.getCreatedAt());
            mapper.insert(e);
            current = e;
        } else {
            // 数据范围就是「这个人能看到什么」。改错了是越权，而越权在界面上看不出来 ——
            // 只会表现为某个人多看到了一些不该看的行。先记后改（就地 set）。
            AuditChanges.record("数据范围", current.getScopeType(), scopeType);
            AuditChanges.record("范围明细", current.getScopeRefs(), refs);
            current.setScopeType(scopeType);
            current.setScopeRefs(refs);
            current.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(current);
        }
        permVersion.bump(); // 在线生效
        return toVO(current);
    }

    private IamDataScope selectOne(String subjectType, String subjectNo) {
        return mapper.selectOne(new LambdaQueryWrapper<IamDataScope>()
                .eq(IamDataScope::getSubjectType, subjectType)
                .eq(IamDataScope::getSubjectNo, subjectNo)
                .last("limit 1"));
    }

    private static DataScopeEntry toVO(IamDataScope e) {
        return new DataScopeEntry(e.getSubjectType(), e.getSubjectNo(), e.getScopeType(), e.getScopeRefs());
    }
}
