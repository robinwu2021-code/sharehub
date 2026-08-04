package ai.neargo.sharehub.platform.md.dto;

/**
 * platform/md 子域出参 VO（第二批：参数字典 / 地区库 / 问题字典 / 多国家市场）。
 *
 * <p><b>为什么另起一个文件而不是往 {@link MdDtos} 追加</b>：{@code MdDtos} 已是先落地的
 * 银行字典金标准样板，多路并行的骨架生成同时改同一个文件必然冲突。域内允许多个 DTO 文件，
 * 语义边界不变（都在 {@code platform.md.dto}）。
 *
 * <p>字段镜像 ops-web {@code lib/types/system.ts} 的同名 interface。
 */
public final class MdDtos2 {

    private MdDtos2() {
    }

    /**
     * 参数字典行，镜像前端 {@code DictEntry}。
     *
     * @param group   实体列名是 {@code group_code}（{@code group} 在多数方言里是保留字），出参保持前端字段名
     * @param enabled 库里是 TINYINT(1)，出参转 boolean
     */
    public record DictEntry(String dictNo, String group, String code, String label,
                            Integer sort, Boolean enabled) {
    }

    /**
     * 地区库行，镜像前端 {@code Region}。
     *
     * @param parent    上级 {@code region_id}（前端 mock 里放的是上级**名称**，后端一律返回**稳定标识**，
     *                  展示名由前端用同一份列表自行映射 —— 避免每行多一次父节点查询）
     * @param cityCount 本表落列的维护值（与 {@code MarketCountry#cityCount} 的聚合口径不同）
     */
    /** 地区树节点。{@code level} 等于树深度，{@code parent} 指向存在的节点（mock 与后端同责）。 */
    public record RegionNode(String regionId, String name, String parent, Integer level,
                             Integer cityCount, java.util.List<RegionNode> children) {
    }

    /** {@code parent} 与 {@code parentId} 双出：前端类型用 parentId，历史页面读 parent，同值。 */
    public record Region(String regionId, String name, String parent, String parentId,
                         Integer level, Integer cityCount) {
    }

    /** 问题字典行，镜像前端 {@code ProblemEntry}（三语 + 建议处置）。 */
    public record ProblemEntry(String problemNo, String category,
                               String title, String titleEn, String titleAr,
                               String answer, String answerEn, String answerAr,
                               String suggestedAction, Integer sortNo, String status) {
    }

    /**
     * 多国家市场行，镜像前端 {@code MarketCountry}。
     *
     * @param cityCount **聚合自 {@code md_region}**（本表无该列，[db-design §1.4]）
     */
    public record MarketCountry(String countryCode, String name, String currency, String timezone,
                                String compliance, Integer cityCount, String status) {
    }

    /** C端 FAQ 条目（{@code GET /mp/faq}）：按语言挑好的单语视图，端上不做三选一。 */
    public record FaqItem(String problemNo, String category, String title, String answer,
                          String suggestedAction, Integer sortNo) {
    }
}
