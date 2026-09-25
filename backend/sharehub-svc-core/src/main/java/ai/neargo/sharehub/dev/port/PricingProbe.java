package ai.neargo.sharehub.dev.port;

/**
 * 上线门禁的「能不能计费」探针。<b>设备域声明、计价域实现</b>（{@code trade.price.port.PricingProbeImpl}）——
 * 依赖方向保持 trade → dev，避免 dev ↔ trade 成环（ArchitectureTest.noCyclesBetweenDomains）。
 */
public interface PricingProbe {

    /** 能为这台设备（按其点位 / 站点上下文）解析出生效中的计费方案时返回方案号，否则 null。 */
    String planFor(String deviceType, String cabinetNo, String locationNo, String siteNo, String agentNo,
                   String vendorCode, String model);
}
