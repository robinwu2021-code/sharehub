package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.NoticeVO;
import ai.neargo.sharehub.user.marketing.entity.MktNotice;
import ai.neargo.sharehub.user.marketing.mapper.MktNoticeMapper;
import ai.neargo.sharehub.user.marketing.service.NoticeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 公告实现：CRUD 走基类，C 端可见性（发布态 + 生效期 + 置顶排序）手写。 */
@Service
public class NoticeServiceImpl extends AbstractCrudService<MktNotice, NoticeVO> implements NoticeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public NoticeServiceImpl(MktNoticeMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "notice_no";
    }

    @Override
    protected String keyOf(MktNotice e) {
        return e.getNoticeNo();
    }

    @Override
    protected void setKey(MktNotice e, String no) {
        e.setNoticeNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.NOTICE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"notice_no", "title", "title_en", "title_ar"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"type", "status"};
    }

    @Override
    protected void beforeCreate(MktNotice e) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus("DRAFT");
        if (e.getType() == null || e.getType().isBlank()) e.setType("SYSTEM");
        if (e.getPinned() == null) e.setPinned(0);
    }

    @Override
    public List<NoticeVO> visibleNotices(Integer limit) {
        String now = LocalDateTime.now().format(TS);
        int n = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);

        LambdaQueryWrapper<MktNotice> w = new LambdaQueryWrapper<MktNotice>()
                .eq(MktNotice::getStatus, "PUBLISHED")
                // 生效期两端可空 = 不限；字符串时间按 ISO 前缀可比，与列的 DATETIME 字典序一致
                .and(q -> q.isNull(MktNotice::getStartAt).or().eq(MktNotice::getStartAt, "").or().le(MktNotice::getStartAt, now))
                .and(q -> q.isNull(MktNotice::getEndAt).or().eq(MktNotice::getEndAt, "").or().ge(MktNotice::getEndAt, now))
                .orderByDesc(MktNotice::getPinned)
                .orderByDesc(MktNotice::getId)
                .last("limit " + n);

        return mapper.selectList(w).stream().map(this::toVO).toList();
    }

    @Override
    protected NoticeVO toVO(MktNotice e) {
        return new NoticeVO(e.getNoticeNo(),
                e.getTitle(), e.getTitleEn(), e.getTitleAr(),
                e.getContent(), e.getContentEn(), e.getContentAr(),
                e.getType(), e.getPinned() != null && e.getPinned() == 1,
                e.getStartAt(), e.getEndAt(),
                e.getStatus(), e.getPublishedBy(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS),
                null); // archivedAt：前端 Archivable 字段，本表无归档列，恒 null
    }
}
