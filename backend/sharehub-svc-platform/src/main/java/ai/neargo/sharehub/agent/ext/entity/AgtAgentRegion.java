package ai.neargo.sharehub.agent.ext.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理辖域（agt_agent_region，[db-design §1.7] 多值列拆表）。
 *
 * <p>前端 {@code Agent.regionScope} 是**逗号串**（渲染方便），
 * **后端不得照抄** —— CSV 存法无法按值检索（"哪些代理管迪拜"要 LIKE 全表扫且会误命中
 * `Dubai-North`）、无法约束合法值（拼错的 region 静默入库）、也无法与 {@code md_region} 建索引关联。
 * 一行一辖区，UK({@code agent_no}, {@code region_id})。
 *
 * <p>CSV ↔ 行的双向转换集中在 {@code AgentRegionService}，不要在各处手写 split。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agt_agent_region")
public class AgtAgentRegion extends BaseEntity {

    private String agentNo;

    /** 辖区（→ {@code md_region.region_id}）。 */
    private String regionId;
}
