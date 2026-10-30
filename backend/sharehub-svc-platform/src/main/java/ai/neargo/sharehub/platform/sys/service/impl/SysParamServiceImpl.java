package ai.neargo.sharehub.platform.sys.service.impl;

import ai.neargo.sharehub.api.platform.port.SysParamWriteGuard;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.sys.SysCtx;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.SysParamEntry;
import ai.neargo.sharehub.platform.sys.entity.SysParam;
import ai.neargo.sharehub.platform.sys.mapper.SysParamMapper;
import ai.neargo.sharehub.platform.sys.service.SysParamService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import ai.neargo.sharehub.platform.sys.port.LocalBizZone;

import java.util.List;

/**
 * 系统参数实现。
 *
 * <p><b>覆盖 {@code selectByKey} 是必须的</b>：基类默认只按业务键单列查，
 * 而本表 UK 是 {@code (tenant_id, param_key)} —— 不带 tenantId 会查到别家的参数。
 */
@Service
public class SysParamServiceImpl extends AbstractCrudService<SysParam, SysParamEntry> implements SysParamService {

    /** 各领域对自己参数的切换规则（如库存引擎），见 {@link SysParamWriteGuard}。 */
    private final List<SysParamWriteGuard> guards;

    public SysParamServiceImpl(SysParamMapper mapper, JdbcTemplate jdbc, List<SysParamWriteGuard> guards) {
        super(mapper, jdbc);
        this.guards = guards == null ? List.of() : guards;
    }

    /**
     * 写入前校验。**放在 service 而不是 controller** —— 新建与更新都走
     * {@code save()}，放 controller 会漏掉其中一条路。
     *
     * <h3>为什么非法值必须在写入时拒</h3>
     * 在读取处兜底（取到脏值就回落默认）会让界面显示「已保存」而实际没生效，
     * 那比直接报错难查得多：运营以为改好了，而系统仍按旧时区算业务日期，
     * 且**每天只有几个小时能看出差别**。
     */
    @Override
    public SysParamEntry save(SysParam body) {
        if (body != null && body.getParamKey() != null && !LocalBizZone.KEY.equals(body.getParamKey())) {
            SysParam current = selectByKey(body.getParamKey());
            lockDictionary(body, current);
            if (current != null) {
                body.setValue(validated(current, body.getValue()));
                if (!java.util.Objects.equals(current.getValue(), body.getValue())) {
                    for (SysParamWriteGuard g : guards) {
                        if (g.handles(body.getParamKey())) g.check(body.getParamKey(), current.getValue(), body.getValue());
                    }
                }
                // 每个参数的取值变化都进审计改前改后：半年后要答得出「当时服务费是多少、谁改的」
                ai.neargo.sharehub.audit.AuditChanges.record(
                        current.getLabel() == null ? current.getParamKey() : current.getLabel(), current.getValue(), body.getValue());
            }
        }
        if (body != null && LocalBizZone.KEY.equals(body.getParamKey())) {
            String v = body.getValue() == null ? "" : body.getValue().trim();
            try {
                java.time.ZoneId.of(v);
            } catch (java.time.DateTimeException e) {
                throw ai.neargo.sharehub.common.BizException.badRequest(
                        "error.sysparam.bad_timezone", v);
            }
            /*
             * 记一条 from→to 进审计详情。**不另起一套留痕** ——
             * AuditTrailInterceptor 已经审计了所有 /api/** 的写操作，
             * 而 AuditChanges 正是它那张「改前改后」表的写侧入口（白名单即调用）。
             *
             * 为什么时区这条值得显式记：半年后查「这笔分润为什么记在 9 月」时，
             * 要能回答「当时的业务时区是什么」。只记 action（POST /sys-params）答不出来，
             * 而当前值正是被改过之后的那个。
             *
             * 先取旧值再 set —— 就地修改的路径过了 setter 旧值就没了（见 AuditChanges 类注释）。
             */
            SysParam old = selectByKey(LocalBizZone.KEY);
            ai.neargo.sharehub.audit.AuditChanges.record(
                    "业务时区", old == null ? null : old.getValue(), v);
            body.setValue(v);
        }
        return super.save(body);
    }

    /** 元数据只由迁移维护：请求体带来的一律丢弃，以库里为准（新建的参数没有元数据 = 自由文本）。 */
    private static void lockDictionary(SysParam body, SysParam current) {
        body.setValueType(current == null ? null : current.getValueType());
        body.setValueOptions(current == null ? null : current.getValueOptions());
        body.setMinValue(current == null ? null : current.getMinValue());
        body.setMaxValue(current == null ? null : current.getMaxValue());
        body.setPattern(current == null ? null : current.getPattern());
        body.setNullable(current == null ? 1 : current.getNullable());
        body.setHint(current == null ? null : current.getHint());
        body.setPendingDecision(current == null ? 0 : current.getPendingDecision());
    }

    /**
     * 按字典校验并归一取值。非法值**写入时拒**（理由见 {@link #save} 的类注释：读取处兜底会让「已保存」与「已生效」脱节）。
     * 归一：去首尾空白；ENUM 转大写；BOOL 认 1/0/true/false 存 1/0；DECIMAL 去掉多余的 0。
     */
    static String validated(SysParam p, String raw) {
        String v = raw == null ? "" : raw.trim();
        String key = p.getParamKey();
        if (v.isEmpty()) {
            if (p.getNullable() != null && p.getNullable() == 0) {
                throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.required", key);
            }
            return "";
        }
        String type = p.getValueType() == null ? "TEXT" : p.getValueType();
        switch (type) {
            case "BOOL" -> {
                if (v.equals("1") || v.equalsIgnoreCase("true")) return "1";
                if (v.equals("0") || v.equalsIgnoreCase("false")) return "0";
                throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.bad_value", key, v, "1 / 0");
            }
            case "INT", "DECIMAL" -> {
                java.math.BigDecimal n;
                try {
                    n = new java.math.BigDecimal(v);
                } catch (NumberFormatException e) {
                    throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.bad_value", key, v, type);
                }
                if ("INT".equals(type) && n.stripTrailingZeros().scale() > 0) {
                    throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.bad_value", key, v, "INT");
                }
                if ((p.getMinValue() != null && n.compareTo(p.getMinValue()) < 0) || (p.getMaxValue() != null && n.compareTo(p.getMaxValue()) > 0)) {
                    throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.out_of_range", key, v,
                            plain(p.getMinValue()), plain(p.getMaxValue()));
                }
                return "INT".equals(type) ? n.toBigInteger().toString() : n.stripTrailingZeros().toPlainString();
            }
            case "ENUM" -> {
                String u = v.toUpperCase();
                java.util.List<ai.neargo.sharehub.platform.sys.dto.SysDtos.ParamOption> opts = optionsOf(p);
                if (opts.stream().noneMatch(o -> o.value().equals(u))) {
                    throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.bad_value", key, v,
                            String.join(" / ", opts.stream().map(ai.neargo.sharehub.platform.sys.dto.SysDtos.ParamOption::value).toList()));
                }
                return u;
            }
            default -> {
                if (p.getPattern() != null && !p.getPattern().isBlank() && !v.matches(p.getPattern())) {
                    throw ai.neargo.sharehub.common.BizException.badRequest("error.sysparam.bad_value", key, v, p.getPattern());
                }
                return v;
            }
        }
    }

    private static String plain(java.math.BigDecimal d) {
        return d == null ? "-" : d.stripTrailingZeros().toPlainString();
    }

    @SuppressWarnings("unchecked")
    static java.util.List<ai.neargo.sharehub.platform.sys.dto.SysDtos.ParamOption> optionsOf(SysParam p) {
        if (p.getValueOptions() == null || p.getValueOptions().isBlank()) return java.util.List.of();
        java.util.List<java.util.Map<String, Object>> raw = ai.neargo.sharehub.common.Json.read(p.getValueOptions(), java.util.List.class);
        if (raw == null) return java.util.List.of();
        return raw.stream().map(m -> new ai.neargo.sharehub.platform.sys.dto.SysDtos.ParamOption(
                String.valueOf(m.get("value")), String.valueOf(m.get("label")))).toList();
    }

    @Override
    protected String keyColumn() {
        return "param_key";
    }

    @Override
    protected String keyOf(SysParam e) {
        return e.getParamKey();
    }

    @Override
    protected void setKey(SysParam e, String no) {
        e.setParamKey(no);
    }

    // keyPrefix() 不覆盖 → 自然键（如 order.max_duration_min），新建必须显式给 paramKey

    /** 复合 UK {@code (tenant_id, param_key)}：按键查询必须带隔离键。 */
    @Override
    protected SysParam selectByKey(String no) {
        if (no == null || no.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<SysParam>()
                .eq("param_key", no)
                .eq("tenant_id", SysCtx.tenantId())
                .last("limit 1"));
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"param_key", "label", "value"};
    }

    @Override
    protected String[] filterFields() {
        // pendingDecision（V191）：系统设置页「只看待拍板」
        return new String[]{"groupName", "pendingDecision"};
    }

    @Override
    protected String orderColumn() {
        return "param_key";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected SysParamEntry toVO(SysParam e) {
        return new SysParamEntry(e.getParamKey(), e.getLabel(), e.getValue(),
                e.getGroupName(), SysCtx.fmt(e.getUpdatedAt()),
                e.getValueType() == null ? "TEXT" : e.getValueType(), optionsOf(e), e.getMinValue(), e.getMaxValue(),
                e.getPattern(), e.getNullable() == null || e.getNullable() != 0, e.getHint(),
                e.getPendingDecision() != null && e.getPendingDecision() == 1);
    }
}
