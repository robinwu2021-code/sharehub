package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.PushMessageVO;
import ai.neargo.sharehub.user.marketing.entity.MktPush;
import ai.neargo.sharehub.user.marketing.mapper.MktPushMapper;
import ai.neargo.sharehub.user.marketing.service.PushService;
import org.springframework.stereotype.Service;

/** 推送触达实现。 */
@Service
public class PushServiceImpl extends AbstractCrudService<MktPush, PushMessageVO> implements PushService {

    public PushServiceImpl(MktPushMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "push_no";
    }

    @Override
    protected String keyOf(MktPush e) {
        return e.getPushNo();
    }

    @Override
    protected void setKey(MktPush e, String no) {
        e.setPushNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.PUSH_MESSAGE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"push_no", "title"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"channel", "status"};
    }

    @Override
    protected void beforeCreate(MktPush e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("DRAFT");
        if (e.getChannel() == null || e.getChannel().isBlank()) e.setChannel("APP_PUSH");
        if (e.getSentCount() == null) e.setSentCount(0);
    }

    @Override
    protected void beforeUpdate(MktPush e, MktPush current) {
        // 已下发的推送不可再改内容/受众 —— 否则历史触达记录与本单不自洽
        if ("SENT".equals(current.getStatus())) {
            e.setTitle(current.getTitle());
            e.setContent(current.getContent());
            e.setAudience(current.getAudience());
            e.setChannel(current.getChannel());
            e.setSentCount(current.getSentCount());
            e.setSentAt(current.getSentAt());
            e.setStatus("SENT");
        }
    }

    @Override
    protected PushMessageVO toVO(MktPush e) {
        return new PushMessageVO(e.getPushNo(), e.getTitle(), e.getChannel(), e.getAudience(),
                e.getSentCount(), e.getStatus(), e.getSentAt());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Object send(String pushNo, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 推送是真推到用户手机上，双击不该推两次。不给键就拒，不"帮它生成"——
            // 生成的话双击会得到两个不同的键，幂等形同虚设。
            throw new IllegalArgumentException("发送推送必须携带 idempotencyKey");
        }
        MktPush e = selectByKey(pushNo);
        if (e == null) throw new IllegalArgumentException("推送不存在: " + pushNo);
        if ("SENT".equals(e.getStatus())) {
            // 幂等：已发送直接返回，不重复推送也不报错。
            return toVO(e);
        }
        e.setStatus("SENT");
        mapper.updateById(e);
        return toVO(selectByKey(pushNo));
    }
}
