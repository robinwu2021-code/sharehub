package ai.neargo.sharehub.platform.md.service.impl;

import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.platform.md.dto.MdDtos2.Region;
import ai.neargo.sharehub.platform.md.entity.MdRegion;
import ai.neargo.sharehub.platform.md.mapper.MdRegionMapper;
import ai.neargo.sharehub.platform.md.service.RegionService;
import org.springframework.stereotype.Service;

/**
 * 地区库实现。{@code region_id} 是自然键（{@code AE}/{@code AE-DU}/{@code DU-MAR}）——
 * 不覆盖 {@code keyPrefix()}，新建时调用方必须显式给键，基类不代为取号。
 */
@Service
public class RegionServiceImpl extends AbstractCrudService<MdRegion, Region> implements RegionService {

    public RegionServiceImpl(MdRegionMapper mapper) {
        super(mapper);
    }

    @Override
    protected String keyColumn() {
        return "region_id";
    }

    @Override
    protected String keyOf(MdRegion e) {
        return e.getRegionId();
    }

    @Override
    protected void setKey(MdRegion e, String no) {
        e.setRegionId(no);
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"region_id", "name", "name_en"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"parentId", "level"};
    }

    @Override
    protected String orderColumn() {
        return "region_id";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    protected void beforeCreate(MdRegion e) {
        if (e.getLevel() == null) e.setLevel(1);
        if (e.getCityCount() == null) e.setCityCount(0);
    }

    @Override
    protected Region toVO(MdRegion e) {
        return new Region(e.getRegionId(), e.getName(), e.getParentId(), e.getParentId(),
                e.getLevel(), e.getCityCount());
    }

    @Override
    public java.util.List<ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode> tree() {
        java.util.List<MdRegion> all = mapper.selectList(null);
        // 先建节点，再连边 —— 一次遍历建不了树（子可能先于父出现）
        java.util.Map<String, java.util.List<ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode>> kids =
                new java.util.HashMap<>();
        for (MdRegion r : all) {
            kids.computeIfAbsent(r.getRegionId(), k -> new java.util.ArrayList<>());
        }
        java.util.List<ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode> roots = new java.util.ArrayList<>();
        java.util.Map<String, ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode> byId = new java.util.HashMap<>();
        for (MdRegion r : all) {
            byId.put(r.getRegionId(), new ai.neargo.sharehub.platform.md.dto.MdDtos2.RegionNode(
                    r.getRegionId(), r.getName(), r.getParentId(), r.getLevel(),
                    r.getCityCount(), kids.get(r.getRegionId())));
        }
        for (MdRegion r : all) {
            String pid = r.getParentId();
            // 父节点缺失的孤儿挂到根，**不丢弃** —— 数据有问题要让运营看得见，
            // 静默丢弃会让「某个城市在页面上消失」变成无从查起的怪事。
            if (pid == null || pid.isBlank() || !byId.containsKey(pid)) {
                roots.add(byId.get(r.getRegionId()));
            } else {
                kids.get(pid).add(byId.get(r.getRegionId()));
            }
        }
        return roots;
    }
}
