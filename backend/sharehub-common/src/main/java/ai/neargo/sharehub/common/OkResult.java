package ai.neargo.sharehub.common;

/**
 * 通用「操作成功」返回壳。
 *
 * <p>原先住在 `dto.Dto` 这个早期共享 DTO 集合里 —— 那是骨架期的产物，
 * 混着旧内存版的业务 DTO（`ShareRule`/`Settlement` 等）。
 * 它本身与业务无关、被 36 个文件使用，**留在业务包里会让每个 svc 模块都被迫依赖 app**，
 * 是拆分的硬阻塞。故上提到 common。
 *
 * <p>判据：一个类型被三个以上服务使用、且不含任何业务语义 → 属于 common。
 */
public record OkResult(boolean ok) {
}
