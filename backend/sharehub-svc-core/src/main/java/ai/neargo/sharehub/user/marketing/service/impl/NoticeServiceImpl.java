package ai.neargo.sharehub.user.marketing.service.impl;

import ai.neargo.sharehub.auth.StaffContext;
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
        // 发布人按当前登录人回填，不接受客户端传 —— 公告是推给 C 端全体用户的，
        // 署名冒名了不报错也看不出来。
        e.setPublishedBy(StaffContext.require().userNo());
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

    /** 发布人是首次发布时的审计事实，编辑不改它（同 {@code FreeWhitelistServiceImpl.grantedBy}）。 */
    @Override
    protected void beforeUpdate(MktNotice e, MktNotice current) {
        e.setPublishedBy(current.getPublishedBy());
        // 归档走 /notices/{no}/archive|unarchive，盖的是时间戳。
        // 保存端点也能写它 = 给归档开了第二条不走审计的路。
        e.setArchivedAt(current.getArchivedAt());
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
                // 2026-09-25：这里原先硬编一个 null，配一句「本表无归档列」。
                // 那句在写下时是对的，**列后来补上了，注释没跟着改** —— 于是运营点完「归档」，
                // 列表刷新回来这一行看上去毫无变化（前端 Notice extends Archivable）。
                // **这是第三次撞见同一形状**：券模板 9-24、充值套餐 9-25、本条。
                // 补列的人只改 DDL 不看读侧，而读侧那句注释长得像结论。
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }
}
