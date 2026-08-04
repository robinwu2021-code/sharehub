package ai.neargo.sharehub.api.remote;

import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import ai.neargo.sharehub.api.gateway.dto.HeartbeatRecord;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.api.gateway.port.TelemetryQueryPort;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 三个 Port 的**远程实现**（ADR-017 §5.2）。
 *
 * <p>调用方永远只依赖接口：单体形态下 classpath 上有本地实现（`Local*`），
 * 微服务形态下没有 → 装配这里的远程实现。**业务代码一字不改。**
 *
 * <p><b>服务端对应的内部端点在各 svc 的 `portal` 里</b>（`/internal/**`），
 * 它们不对外暴露、不进 openapi 文档。
 */
public final class RemotePorts {

    private RemotePorts() {
    }

    /** 场地查询 → platform。 */
    public static final class RemoteSiteQuery implements SiteQueryPort {

        private static final ParameterizedTypeReference<RemoteCaller.Envelope<List<SiteBrief>>> T =
                new ParameterizedTypeReference<>() {
                };

        private final RemoteCaller caller;

        public RemoteSiteQuery(RemoteCaller caller) {
            this.caller = caller;
        }

        @Override
        public List<SiteBrief> briefsByNos(Collection<String> siteNos) {
            if (siteNos == null || siteNos.isEmpty()) return List.of();
            // 批量走 POST 而非 GET：站点编号可能几百个，塞进 query string 会超长度限制。
            return caller.post("platform", "/internal/platform/sites/briefs", siteNos, T);
        }
    }

    /** 遥测查询 → device-gateway。 */
    public static final class RemoteTelemetryQuery implements TelemetryQueryPort {

        private static final ParameterizedTypeReference<RemoteCaller.Envelope<List<HeartbeatRecord>>> T =
                new ParameterizedTypeReference<>() {
                };

        private final RemoteCaller caller;

        public RemoteTelemetryQuery(RemoteCaller caller) {
            this.caller = caller;
        }

        @Override
        public List<HeartbeatRecord> recentHeartbeats(String cabinetNo, Integer limit) {
            return caller.get("device-gateway", "/internal/gw/heartbeats",
                    Map.of("cabinetNo", cabinetNo, "limit", limit == null ? 50 : limit), T);
        }
    }

    /** 机柜只读查询 → core。 */
    public static final class RemoteCabinetQuery implements CabinetQueryPort {

        private static final ParameterizedTypeReference<RemoteCaller.Envelope<List<CabinetBrief>>> T =
                new ParameterizedTypeReference<>() {
                };

        private final RemoteCaller caller;

        public RemoteCabinetQuery(RemoteCaller caller) {
            this.caller = caller;
        }

        @Override
        public List<CabinetBrief> assignable(String keyword, String agentNo, Integer limit) {
            Map<String, Object> q = new java.util.HashMap<>();
            if (keyword != null && !keyword.isBlank()) q.put("keyword", keyword);
            if (agentNo != null && !agentNo.isBlank()) q.put("agentNo", agentNo);
            q.put("limit", limit == null ? 100 : limit);
            return caller.get("core", "/internal/dev/cabinets/assignable", q, T);
        }

        @Override
        public List<CabinetBrief> briefsByNos(Collection<String> cabinetNos) {
            if (cabinetNos == null || cabinetNos.isEmpty()) return List.of();
            // 与 RemoteSiteQuery.briefsByNos 同理走 POST：编号可能几百个，query string 会超长
            return caller.post("core", "/internal/dev/cabinets/briefs", cabinetNos, T);
        }
    }

    /** 设备归属回写 → core。 */
    public static final class RemoteDeviceOwnership implements DeviceOwnershipPort {

        private static final ParameterizedTypeReference<RemoteCaller.Envelope<Integer>> INT =
                new ParameterizedTypeReference<>() {
                };
        private static final ParameterizedTypeReference<RemoteCaller.Envelope<Boolean>> BOOL =
                new ParameterizedTypeReference<>() {
                };

        private final RemoteCaller caller;

        public RemoteDeviceOwnership(RemoteCaller caller) {
            this.caller = caller;
        }

        @Override
        public int reassignByLocations(List<String> locationNos, String agentNo, String siteNo) {
            return caller.post("core", "/internal/dev/ownership/by-locations",
                    Map.of("locationNos", locationNos,
                            "agentNo", agentNo == null ? "" : agentNo,
                            "siteNo", siteNo == null ? "" : siteNo), INT);
        }

        @Override
        public int reassignCabinet(String cabinetNo, String agentNo) {
            return caller.post("core", "/internal/dev/ownership/cabinet",
                    Map.of("cabinetNo", cabinetNo, "agentNo", agentNo == null ? "" : agentNo), INT);
        }

        @Override
        public boolean existsInScope(String cabinetNo) {
            // ⚠️ 数据范围是**调用方会话**的属性，跨进程后必须由受信头透传（X-User-Id/X-Roles）。
            // 当前 RemoteCaller 未透传 —— 见 RemotePortsAutoConfiguration 的已知局限。
            return Boolean.TRUE.equals(caller.get("core", "/internal/dev/ownership/exists",
                    Map.of("cabinetNo", cabinetNo), BOOL));
        }
    }
}
