package ai.neargo.sharehub.user.ad.service.impl;

import ai.neargo.sharehub.user.ad.AdSlotStatus;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdSlotVO;
import ai.neargo.sharehub.user.ad.entity.AdSlot;
import ai.neargo.sharehub.user.ad.mapper.AdSlotMapper;
import ai.neargo.sharehub.user.ad.service.AdSlotService;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;

/** 广告位实现。 */
@Service
public class AdSlotServiceImpl extends AbstractCrudService<AdSlot, AdSlotVO> implements AdSlotService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AdSlotServiceImpl(AdSlotMapper mapper, JdbcTemplate jdbc) {
        super(mapper, jdbc);
    }

    @Override
    protected String keyColumn() {
        return "slot_no";
    }

    @Override
    protected String keyOf(AdSlot e) {
        return e.getSlotNo();
    }

    @Override
    protected void setKey(AdSlot e, String no) {
        e.setSlotNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.AD_SLOT;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"slot_no", "cabinet_no"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"cabinetNo", "position", "status"};
    }

    @Override
    protected void beforeCreate(AdSlot e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(AdSlotStatus.IDLE.name());
        /*
         * 补默认值 ≠ 校验传进来的值：前者只管「没传时填什么」，
         * 传了非法值照样落库。ad_slot 现在 0 行，装闸趁早。
         *
         * ⚠️ 顺带一个发现：DDL 里 ad_slot.type 有词表（BODY 机身贴 / SCREEN 屏幕），
         * 而**实体根本没映射这一列** —— 广告位类型存不进也读不出，界面上没有这一格。
         * 不在这里顺手加字段：那是补功能，要先确认广告位要不要按类型区分计价。
         */
        AdSlotStatus.of(e.getStatus());
        if (e.getPosition() == null || e.getPosition().isBlank()) e.setPosition("SCREEN");
    }

    @Override
    protected AdSlotVO toVO(AdSlot e) {
        return new AdSlotVO(e.getSlotNo(), e.getCabinetNo(), e.getPosition(), e.getSize(), e.getStatus(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
    }
}
