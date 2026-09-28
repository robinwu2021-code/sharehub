package ai.neargo.sharehub.platform.sys.service.impl;

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

/**
 * 系统参数实现。
 *
 * <p><b>覆盖 {@code selectByKey} 是必须的</b>：基类默认只按业务键单列查，
 * 而本表 UK 是 {@code (tenant_id, param_key)} —— 不带 tenantId 会查到别家的参数。
 */
@Service
public class SysParamServiceImpl extends AbstractCrudService<SysParam, SysParamEntry> implements SysParamService {

    public SysParamServiceImpl(SysParamMapper mapper, JdbcTemplate jdbc) {
        super(mapper, jdbc);
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
        return new String[]{"groupName"};
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
                e.getGroupName(), SysCtx.fmt(e.getUpdatedAt()));
    }
}
