package ai.neargo.sharehub.platform.notify.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos;
import ai.neargo.sharehub.platform.notify.dto.NotifyDtos.NotifyTemplateVO;
import ai.neargo.sharehub.platform.notify.entity.NotifyTemplate;

/** 通知模板。纯配置读写，无业务规则 → 继承通用 CRUD。 */
public interface NotifyTemplateService extends CrudService<NotifyTemplate, NotifyTemplateVO> {

    /**
     * 渲染预览：把 {@code {{var}}} 占位替换成给定值。
     *
     * <p><b>缺失的变量原样保留并单列出来</b>，不静默替成空串 ——
     * 运营看到 `尊敬的{{name}}` 就知道漏配了；替成空串则是「尊敬的，」，上线后才发现。
     */
    NotifyDtos.NotifyTemplatePreviewVO preview(String templateNo, java.util.Map<String, String> vars);

    /**
     * 试发：按模板真发一条给指定目标，并落 {@code notify_log}。
     *
     * <p><b>必带幂等键</b> —— 试发也是真发真扣钱，双击提交不该发两条。
     */
    NotifyDtos.NotifyLogVO testSend(String templateNo, NotifyDtos.NotifyTestSendReq req);
}
