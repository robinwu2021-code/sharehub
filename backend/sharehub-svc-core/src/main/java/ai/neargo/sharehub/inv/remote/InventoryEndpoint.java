package ai.neargo.sharehub.inv.remote;

/**
 * 库存服务寻址：调用前问一次「现在该打哪个地址」。
 *
 * <p>业务代码只认 {@link InventoryApi}，HTTP 客户端只认本接口 —— 换寻址方式（固定地址 → 服务发现）不动这两层。
 * <ul>
 *   <li><b>默认</b>：{@link StaticInventoryEndpoint}，读 {@code sharehub.inventory.base-url}，可以是 IP、域名或
 *       {@code /etc/hosts} 名（技术架构方案 v4 §8.1：不引服务发现中间件）。</li>
 *   <li><b>服务发现</b>（Nacos / Consul / k8s）：在独立的可选模块里提供一个实现并注册为 Bean，默认实现自动退让
 *       （{@code @ConditionalOnMissingBean}）。按 {@code sharehub.inventory.service-id} 取实例，每次调用重新挑一个，
 *       {@link #failed} 收到连不上的实例时摘除。写法见 docs/technical/库存系统对接方案.md §十二。</li>
 * </ul>
 */
public interface InventoryEndpoint {

    /** 寻址方式：STATIC / DISCOVERY（展示与排障用）。 */
    String mode();

    /** 是否已配置（静态：配了地址；发现：配了服务名）。 */
    boolean configured();

    /** 本次调用的服务根地址，如 {@code http://127.0.0.1:8088}，不带结尾斜杠、不带 {@code /api/inventory/v1}。 */
    String baseUrl();

    /** 调用连不上时回报（发现模式可据此暂时摘除实例；静态模式忽略）。 */
    default void failed(String baseUrl, Exception cause) {
    }
}
