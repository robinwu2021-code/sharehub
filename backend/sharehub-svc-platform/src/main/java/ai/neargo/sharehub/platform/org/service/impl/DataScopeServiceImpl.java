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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        String refs = NO_REF_SCOPES.contains(scopeType) ? null : toJsonArray(scopeRefs);

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
        return new DataScopeEntry(e.getSubjectType(), e.getSubjectNo(), e.getScopeType(),
                toCsv(e.getScopeRefs()));
    }

    /**
     * 逗号串 → JSON 数组文本。
     *
     * <p><b>{@code scope_refs} 是 JSON 列</b>（V1 建表），MariaDB 的 JSON 是带
     * {@code json_valid} 约束的 LONGTEXT。此前这里把前端传来的逗号串原样落库 ——
     * 于是**只要选了 ALL/SELF 之外的任何范围，保存就 500**。
     * mock 下一路顺，切真后端当场炸，而且炸在一个没人会去点第二次的抽屉里。
     *
     * <p>入参两种形态都收（前端传逗号串，历史数据是 JSON），出参统一成逗号串 ——
     * 「存什么形态」是库的事，接口不该把它漏给前端。
     */
    static String toJsonArray(String raw) {
        List<String> items = split(raw);
        if (items.isEmpty()) return null;
        return items.stream()
                // 范围值是业务编号（ST301 / AE-DU / AG002），不会出现引号与反斜杠；
                // 真出现了就是脏数据，直接剔掉而不是拼出一段非法 JSON 让整条保存失败。
                .filter(v -> v.indexOf('"') < 0 && v.indexOf('\\') < 0)
                .map(v -> '"' + v + '"')
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** JSON 数组文本 → 逗号串（前端抽屉绑的就是逗号串）。 */
    static String toCsv(String raw) {
        List<String> items = split(raw);
        return items.isEmpty() ? null : String.join(",", items);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }
}
