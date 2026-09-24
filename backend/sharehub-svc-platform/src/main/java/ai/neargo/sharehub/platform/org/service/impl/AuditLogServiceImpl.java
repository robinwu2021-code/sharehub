package ai.neargo.sharehub.platform.org.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditDetail;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditFieldChange;
import ai.neargo.sharehub.platform.org.dto.OrgDtos.AuditLogEntry;
import ai.neargo.sharehub.platform.org.entity.IamAuditLog;
import ai.neargo.sharehub.platform.org.mapper.IamAuditLogMapper;
import ai.neargo.sharehub.platform.org.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作审计实现 —— **append + query only**。
 *
 * <p>{@code IamAuditLogMapper} 继承 {@code BaseMapper} 因而技术上带着 update/delete，
 * 但本类**不暴露也不调用**它们，控制器层同样没有对应端点：WORM 语义靠「服务契约不给口子」
 * 保证（见 {@link AuditLogService} 类注释）。物理层的加固（触发器/权限收紧）留给 DBA。
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final String TENANT_MAIN = "MAIN";
    /** 「无此值」占位（新增记录的 before / 删除记录的 after）；与前端 {@code AuditFieldChange} 约定一致，不可用空串。 */
    static final String DASH = "—";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IamAuditLogMapper mapper;

    public AuditLogServiceImpl(IamAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<AuditLogEntry> page(Integer page, Integer size, String keyword,
                                          String actor, String action, String targetType) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<IamAuditLog> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(IamAuditLog::getActor, keyword)
                    .or().like(IamAuditLog::getActorName, keyword)
                    .or().like(IamAuditLog::getAction, keyword)
                    .or().like(IamAuditLog::getTargetNo, keyword));
        }
        if (actor != null && !actor.isBlank()) w.eq(IamAuditLog::getActor, actor);
        if (action != null && !action.isBlank()) w.eq(IamAuditLog::getAction, action);
        if (targetType != null && !targetType.isBlank()) w.eq(IamAuditLog::getTargetType, targetType);
        w.orderByDesc(IamAuditLog::getId); // 时间倒序；id 单调，比 created_at 排序更稳（同毫秒不乱序）

        Page<IamAuditLog> r = mapper.selectPage(new Page<>(p, s), w);
        List<AuditLogEntry> rows = r.getRecords().stream().map(AuditLogServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    /**
     * 单条详情。id 非数字（种子的 {@code A9000} 形态）或库里查不到 → 返回 null 交控制器回落。
     *
     * <p>{@code requestId}/{@code userAgent} 现有 DDL 无对应列，一律出空串；
     * {@code changes} 只从 {@code detail} 这一列的 JSON 里**读**（见 {@link #changesOf}），
     * 读不出就是空数组 —— 绝不按「猜 action 语义」编造前后值。
     */
    @Override
    public AuditDetail detail(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        long pk;
        try {
            pk = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return null;   // 非自增主键形态 → 不是库里的行
        }
        IamAuditLog e = mapper.selectById(pk);
        if (e == null) {
            return null;
        }
        AuditLogEntry row = toVO(e);
        return new AuditDetail(row.id(), row.actor(), row.actorName(), row.clientCode(), row.action(),
                row.targetType(), row.targetNo(), row.target(), row.detail(), row.ip(), row.createdAt(),
                "", "", changesOf(row.detail()));
    }

    /**
     * 从 {@code detail}（JSON 文本列）里尽力提取字段级改动。
     *
     * <p>约定：{@code {"changes":[{"field":"name","before":"A","after":"B"}, ...]}}。
     * 这是**读侧的宽松约定**，不是新表结构 —— detail 是本表唯一能容纳 diff 的列，
     * 将来写侧（append 的调用方）按此形状塞，详情页立刻就有内容，无需 ALTER。
     *
     * <p>解析失败/无该键/形状不符 → 空数组。审计读接口**宁可少给也不能给假的**，
     * 所以这里既不抛错（一条脏 detail 不该让整个详情 500），也不做任何推断补全。
     */
    private static List<AuditFieldChange> changesOf(String detail) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(detail);
            JsonNode arr = node.path("changes");
            if (!arr.isArray()) {
                return List.of();
            }
            List<AuditFieldChange> out = new ArrayList<>();
            for (JsonNode c : arr) {
                String field = c.path("field").asText("");
                if (field.isBlank()) {
                    continue;   // 没有字段名的 diff 项无意义，丢弃
                }
                out.add(new AuditFieldChange(field, c.path("before").asText(DASH), c.path("after").asText(DASH)));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();   // detail 不是 JSON（历史行常是纯文本摘要）→ 无 diff
        }
    }

    @Override
    public void append(String actor, String actorName, String clientCode, String action,
                       String targetType, String targetNo, String detail, String ip) {
        IamAuditLog e = new IamAuditLog();
        e.setTenantId(TENANT_MAIN);
        e.setActor(actor);
        e.setActorName(actorName);
        e.setClientCode(clientCode);
        e.setAction(action);
        e.setTargetType(targetType);
        e.setTargetNo(targetNo);
        e.setDetail(detail);
        e.setIp(ip);
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
    }

    private static AuditLogEntry toVO(IamAuditLog e) {
        String targetType = unjson(e.getTargetType());
        String targetNo = unjson(e.getTargetNo());
        String target = targetType == null ? targetNo
                : targetType + ":" + (targetNo == null ? "" : targetNo);
        return new AuditLogEntry(String.valueOf(e.getId()), e.getActor(), e.getActorName(),
                e.getClientCode(), e.getAction(),
                targetType, targetNo, target, unjson(e.getDetail()), e.getIp(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }

    /**
     * JSON 列解包：{@code target_no}/{@code target_type}/{@code detail} 是 JSON 类型列
     * （V13 对账产物，带 {@code json_valid} CHECK），纯文本值以 JSON 字符串字面量存储
     * （{@code "CAB1001"} 带引号）。出参还原为业务值；非字符串 JSON（对象/数组）原样透出。
     */
    private static String unjson(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"') {
            return raw;
        }
        return ai.neargo.sharehub.common.Json.read(raw, String.class, raw);
    }
}
