package ai.neargo.sharehub.common.crud;

import java.time.LocalDateTime;

/**
 * 可归档主数据（前端契约 {@code Archivable}）。
 *
 * <p><b>为什么是时间戳而不是 {@code deleted} 布尔</b>：归档时间本身就是审计信息 ——
 * 布尔位丢掉了「什么时候没的」，出问题时无从回溯。{@code null} = 在用，非空 = 已归档。
 *
 * <p><b>与 {@code BaseEntity.deleted} 是两回事，不要合并</b>：
 * <ul>
 *   <li>{@code deleted} —— MyBatis-Plus {@code @TableLogic} 的技术软删，查询默认不可见，
 *       运营端既看不到也恢复不了；</li>
 *   <li>{@code archivedAt} —— 业务层面的「停用但保留」，运营端可勾选「显示已归档」查看、可恢复。</li>
 * </ul>
 * 全平台不做物理删除，契约里禁止出现 {@code delete*}。
 */
public interface Archivable {

    LocalDateTime getArchivedAt();

    void setArchivedAt(LocalDateTime at);
}
