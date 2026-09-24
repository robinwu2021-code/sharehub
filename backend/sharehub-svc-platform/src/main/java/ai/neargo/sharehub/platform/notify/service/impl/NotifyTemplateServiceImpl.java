package ai.neargo.sharehub.platform.notify.service.impl;

import ai.neargo.sharehub.platform.notify.NotifyTemplateStatus;
import ai.neargo.sharehub.platform.notify.NotifyLogStatus;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.platform.notify.service.NotifyLogService;
import ai.neargo.sharehub.platform.notify.entity.NotifyLog;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyTemplateVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyTemplate;
import ai.neargo.sharehub.platform.notify.mapper.NotifyTemplateMapper;
import ai.neargo.sharehub.platform.notify.service.NotifyTemplateService;
import org.springframework.stereotype.Service;

/** 通知模板实现 —— 字典/配置类，行为全部来自 {@link AbstractCrudService}。 */
@Service
public class NotifyTemplateServiceImpl extends AbstractCrudService<NotifyTemplate, NotifyTemplateVO>
        implements NotifyTemplateService {

    private final NotifyLogService notifyLogService;

    public NotifyTemplateServiceImpl(NotifyTemplateMapper mapper, NotifyLogService notifyLogService) {
        super(mapper);
        this.notifyLogService = notifyLogService;
    }

    @Override
    protected String keyColumn() {
        return "template_no";
    }

    @Override
    protected String keyOf(NotifyTemplate e) {
        return e.getTemplateNo();
    }

    @Override
    protected void setKey(NotifyTemplate e, String no) {
        e.setTemplateNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.NOTIFY_TEMPLATE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"template_no", "name", "scene", "content"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"channel", "lang", "scene", "status"};
    }

    @Override
    protected String orderColumn() {
        return "template_no";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(NotifyTemplate e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(NotifyTemplateStatus.ENABLED.name());
        if (e.getLang() == null || e.getLang().isBlank()) e.setLang("en");
    }

    @Override
    protected NotifyTemplateVO toVO(NotifyTemplate e) {
        return new NotifyTemplateVO(e.getTemplateNo(), e.getName(), e.getChannel(), e.getLang(),
                e.getScene(), e.getContent(), e.getParams(), e.getStatus());
    }

    // ───────────────── 预览 / 试发 ─────────────────

    /** {@code {{var}}} 占位符。两侧允许空格，运营手写模板时常带。 */
    private static final java.util.regex.Pattern VAR =
            java.util.regex.Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    @Override
    public NotifyDtos.NotifyTemplatePreviewVO preview(String templateNo, java.util.Map<String, String> vars) {
        NotifyTemplate t = selectByKey(templateNo);
        if (t == null) throw new IllegalArgumentException("模板不存在: " + templateNo);
        java.util.Map<String, String> v = vars == null ? java.util.Map.of() : vars;

        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.regex.Matcher m = VAR.matcher(t.getContent() == null ? "" : t.getContent());
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = v.get(key);
            if (val == null) {
                // **缺失的变量原样保留**，不静默替空串 —— 运营看到 `尊敬的{{name}}` 就知道漏配了；
                // 替成空串是「尊敬的，」，上线后才发现。
                if (!missing.contains(key)) missing.add(key);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
            }
        }
        m.appendTail(sb);
        return new NotifyDtos.NotifyTemplatePreviewVO(
                t.getTemplateNo(), t.getChannel(), sb.toString(), missing);
    }

    @Override
    public NotifyDtos.NotifyLogVO testSend(String templateNo, NotifyDtos.NotifyTestSendReq req) {
        if (req == null || req.target() == null || req.target().isBlank()) {
            throw new IllegalArgumentException("试发必须指定目标");
        }
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            // 试发也是真发真扣钱，双击提交不该发两条。
            throw new IllegalArgumentException("试发必须携带 idempotencyKey");
        }
        NotifyDtos.NotifyTemplatePreviewVO p = preview(templateNo, req.vars());
        if (!p.missingVars().isEmpty()) {
            // 变量没配全就发出去，用户会收到带 {{}} 的短信。宁可拒绝。
            throw new IllegalArgumentException("模板变量未配全，缺: " + p.missingVars());
        }
        NotifyTemplate t = selectByKey(templateNo);

        NotifyLog log = new NotifyLog();
        log.setChannel(t.getChannel());
        log.setTemplateNo(templateNo);
        log.setTarget(req.target());          // append() 会做脱敏，明文不入库
        log.setScene("TEST_SEND");
        log.setStatus(NotifyLogStatus.SENT.name());
        log.setIdempotencyKey(req.idempotencyKey());
        return notifyLogService.append(log);
    }
}
