package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.AgentBrief;

import java.util.Collection;
import java.util.Map;

/**
 * 代理商名录只读查询 —— agent 域暴露给**同模块其他域**的读面。
 *
 * <h2>为什么需要这个 Port</h2>
 * 「站点伙伴责任」（loc 域）要把 {@code agentNo} 显示成名字与类型。最直接的写法是
 * 注入 {@code AgentMapper} 读 {@code AgtAgent} —— 2026-09-23 的实现正是这样，
 * 结果造出了本仓**第一个包循环**：{@code agent → loc}（划拨回写归属列，ADR-012）
 * 与 {@code loc → agent}（读名字）首尾相接。
 *
 * <h2>为什么方向是 agent 暴露给 loc，而不是反过来</h2>
 * 业务上**站点不依赖代理商**：站点是物理场所，先有场地后有代理，
 * 平台直营的站点一个代理都没有也完整存在。而代理商的本质就是「包一片站点」，
 * 脱离站点没有意义。所以 {@code agent → loc} 是本分，{@code loc → agent} 是越界 ——
 * 越界的那一侧改走契约，环就消失。
 *
 * <h2>只读是刻意的</h2>
 * 代理商的写入口在 agent 自己的 Controller。开放写接口等于让别的域绕过它的校验
 * 改它的数据（同 {@code SiteQueryPort} 的理由）。
 *
 * <p>S5 拆服务后本 Port 可换远程实现（见 {@code api/remote/}）——
 * 而直接 import mapper 的写法拆不动，这是 Port 化的第二个收益。
 */
public interface AgentDirectoryPort {

    /**
     * 批量取名录，一次回表避免 N+1。
     *
     * @return {@code agentNo → brief}；查不到的编号**不出现在结果里**（不塞 null 占位）
     */
    Map<String, AgentBrief> briefsOf(Collection<String> agentNos);

    /** 单个取；不存在返回 {@code null}。用于写入前的存在性校验。 */
    AgentBrief briefOf(String agentNo);
}
