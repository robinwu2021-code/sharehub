package ai.neargo.sharehub.user.ad.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.ad.dto.AdDtos.AdSlotVO;
import ai.neargo.sharehub.user.ad.entity.AdSlot;
import ai.neargo.sharehub.user.ad.mapper.AdSlotMapper;
import ai.neargo.sharehub.user.ad.service.AdSlotService;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/** 广告位实现。 */
@Service
public class AdSlotServiceImpl extends AbstractCrudService<AdSlot, AdSlotVO> implements AdSlotService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AdSlotServiceImpl(AdSlotMapper mapper) {
        super(mapper);
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
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("IDLE");
        if (e.getPosition() == null || e.getPosition().isBlank()) e.setPosition("SCREEN");
    }

    @Override
    protected AdSlotVO toVO(AdSlot e) {
        return new AdSlotVO(e.getSlotNo(), e.getCabinetNo(), e.getPosition(), e.getSize(), e.getStatus(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
    }
}
