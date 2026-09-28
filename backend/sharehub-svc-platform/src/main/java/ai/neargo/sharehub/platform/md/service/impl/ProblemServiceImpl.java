package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.platform.md.ProblemStatus;
import ai.neargo.sharehub.platform.md.ProblemSuggestedAction;
import ai.neargo.sharehub.platform.md.ProblemCategory;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.FaqItem;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.ProblemEntry;
import ai.neargo.sharehub.platform.md.entity.MdProblem;
import ai.neargo.sharehub.platform.md.mapper.MdProblemMapper;
import ai.neargo.sharehub.platform.md.service.ProblemService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 问题字典实现。运营侧走通用 CRUD；{@link #faq} 是 C 端只读投影
 * —— 同一份数据既喂运营端「问题管理」，也喂 C 端帮助中心与报障下拉。
 */
@Service
public class ProblemServiceImpl extends AbstractCrudService<MdProblem, ProblemEntry> implements ProblemService {

    private final MdProblemMapper problemMapper;

    public ProblemServiceImpl(MdProblemMapper mapper, JdbcTemplate jdbc) {
        super(mapper, jdbc);
        this.problemMapper = mapper;
    }

    @Override
    protected String keyColumn() {
        return "problem_no";
    }

    @Override
    protected String keyOf(MdProblem e) {
        return e.getProblemNo();
    }

    @Override
    protected void setKey(MdProblem e, String no) {
        e.setProblemNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.PROBLEM; // ISS —— 勿与充电宝 PB 混用
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"problem_no", "title", "title_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"category", "suggestedAction", "status"};
    }

    @Override
    protected String orderColumn() {
        return "sort_no";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(MdProblem e) {
        if (e.getSortNo() == null) e.setSortNo(0);
        if (e.getStatus() == null) e.setStatus(ProblemStatus.ENABLED.name());
        if (e.getSuggestedAction() == null) e.setSuggestedAction(ProblemSuggestedAction.SELF_SERVICE.name());
        validateVocabulary(e);
    }

    @Override
    protected void beforeUpdate(MdProblem e, MdProblem current) {
        if (e.getStatus() == null || e.getStatus().isBlank()) e.setStatus(current.getStatus());
        validateVocabulary(e);
    }

    /**
     * 两列词表此前**一处都没校验**，实测 POST {@code category=HARDWARE} 与
     * {@code suggestedAction=TO_MANAGER} 都是 200 落库。
     *
     * <p>这里原本只有 {@code beforeCreate} 里那几行补默认值 —— 而「补默认值」与
     * 「校验传进来的值」是两件事：没传时填一个对的，传了错的照样存。
     * 编辑路径连默认值都没有，所以本条也补了 {@code beforeUpdate}。
     *
     * <p>{@code suggestedAction} 尤其不该漏：它不是展示字段而是**路由依据**
     * （自助 / 客服 / 退款 / 工单），写错的后果是用户被送到错的地方，
     * 而他不知道本该去别处。
     */
    private static void validateVocabulary(MdProblem e) {
        ProblemCategory.ofNullable(e.getCategory());
        ProblemSuggestedAction.ofNullable(e.getSuggestedAction());
        // status 是第三列，同样没校验过：C 端 FAQ 按 status='ENABLED' 筛，
        // 写错一个字母这条问题就从用户那边消失，而运营端列表里它好好地在
        ProblemStatus.of(e.getStatus());
    }

    @Override
    public List<FaqItem> faq(String category, String lang) {
        LambdaQueryWrapper<MdProblem> w = new LambdaQueryWrapper<MdProblem>()
                .eq(MdProblem::getStatus, "ENABLED");
        if (category != null && !category.isBlank()) w.eq(MdProblem::getCategory, category);
        w.orderByAsc(MdProblem::getSortNo).orderByAsc(MdProblem::getId);

        return problemMapper.selectList(w).stream()
                .map(e -> new FaqItem(e.getProblemNo(), e.getCategory(),
                        pick(lang, e.getTitle(), e.getTitleEn(), e.getTitleAr()),
                        pick(lang, e.getAnswer(), e.getAnswerEn(), e.getAnswerAr()),
                        e.getSuggestedAction(), e.getSortNo()))
                .toList();
    }

    /** 三语取一：目标语言列为空时回落默认列，绝不给端上返回空标题。 */
    private static String pick(String lang, String def, String en, String ar) {
        String v = "en".equalsIgnoreCase(lang) ? en : ("ar".equalsIgnoreCase(lang) ? ar : def);
        return (v == null || v.isBlank()) ? def : v;
    }

    @Override
    protected ProblemEntry toVO(MdProblem e) {
        return new ProblemEntry(e.getProblemNo(), e.getCategory(),
                e.getTitle(), e.getTitleEn(), e.getTitleAr(),
                e.getAnswer(), e.getAnswerEn(), e.getAnswerAr(),
                e.getSuggestedAction(), e.getSortNo(), e.getStatus(),
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }
}
