package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.FaqItem;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.ProblemEntry;
import ai.neargo.sharehub.platform.md.entity.MdProblem;

import java.util.List;

/**
 * 问题字典。运营侧是纯字典 CRUD；额外给 C 端一个只读投影
 * —— 报障分流与帮助中心共用同一份数据，避免两处维护。
 */
public interface ProblemService extends CrudService<MdProblem, ProblemEntry> {

    /**
     * C端 FAQ / 报障问题列表（只取 {@code ENABLED}，按 {@code sortNo} 升序）。
     *
     * @param category 可空；空则全部分类
     * @param lang     {@code zh}(默认列) / {@code en} / {@code ar}；缺省回落默认列
     */
    List<FaqItem> faq(String category, String lang);
}
