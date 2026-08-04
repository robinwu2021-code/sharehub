package ai.neargo.sharehub.platform.md.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.Region;
import ai.neargo.sharehub.platform.md.entity.MdRegion;

/** 地区库。纯主数据读写 → 继承通用 CRUD；{@code region_id} 是自然键，新建必须显式给。 */
public interface RegionService extends CrudService<MdRegion, Region> {

    /**
     * 地区树。
     *
     * <p><b>一次查全表在内存里建树</b>，不做递归查询：地区总量是百量级且极少变动，
     * 递归 SQL 的复杂度换不来任何收益。
     *
     * <p><b>父节点缺失的孤儿挂到根</b>而不是丢弃 —— 数据有问题时要让运营看得见，
     * 静默丢弃会让「某个城市在页面上消失」变成无从查起的怪事。
     */
    java.util.List<ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode> tree();
}
