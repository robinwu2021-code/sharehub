package ai.neargo.powerbank.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 实体基类：物理主键（库内自增 {@link IdType#AUTO} Long）+ 隔离键 + 审计 + 乐观锁 + 逻辑删除。
 *
 * <p>镜像 neargo-common-data {@code BaseEntity} 的意图，但适配 powerbank（[ADR-014]/[TDD-接入层分端与common复用 §6]）：
 * <ul>
 *   <li>隔离键用 {@code tenant_id}（String，ADR-007）而非 neargo v2 的 {@code region_id/merchant_id}——
 *       powerbank 表无 {@code region_id} 列，故不直接继承 neargo BaseEntity；</li>
 *   <li>{@code deleted} 用 {@code Integer}（TINYINT(1)）贴合现有 schema；</li>
 *   <li>暂不加 {@code @TableField(fill=…)}：powerbank 未注册 {@code MetaObjectHandler}（其 {@code MybatisPlusConfig}
 *       在并行「权限对话」维护），审计自动填充留待协调（M5 后续）。</li>
 * </ul>
 * <p><b>主键约定</b>：{@code id} 为库内自增物理主键、聚簇、<b>不跨库不对外</b>；对外稳定标识用各聚合根
 * 业务键 {@code <x>No}（域列，由 {@code ai.neargo.common.core.IdGenerator} 生成）。
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @TableLogic
    private Integer deleted;
}
