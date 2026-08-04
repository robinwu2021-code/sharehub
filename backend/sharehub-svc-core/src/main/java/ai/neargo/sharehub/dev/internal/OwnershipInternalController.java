package ai.neargo.sharehub.dev.internal;

import ai.neargo.sharehub.api.core.port.DeviceOwnershipPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@link DeviceOwnershipPort} 的服务端（仅服务间调用）。
 *
 * <p>⚠️ {@code existsInScope} 是**带数据范围的授权闸门**，其结果取决于**调用方会话**。
 * 跨进程后必须由受信头透传身份，否则这里拿到的是「无身份」上下文、判定结果不正确。
 * 受信头透传尚未实现（见 `RemotePortsAutoConfiguration` 的已知局限），
 * **故本端点在微服务形态下暂不可依赖**。
 */
@RestController
public class OwnershipInternalController {

    private final DeviceOwnershipPort ownership;

    public OwnershipInternalController(DeviceOwnershipPort ownership) {
        this.ownership = ownership;
    }

    @PostMapping("/internal/dev/ownership/by-locations")
    @SuppressWarnings("unchecked")
    public int byLocations(@RequestBody Map<String, Object> body) {
        List<String> locationNos = (List<String>) body.getOrDefault("locationNos", List.of());
        String agentNo = str(body.get("agentNo"));
        String siteNo = str(body.get("siteNo"));
        return ownership.reassignByLocations(locationNos, agentNo, siteNo);
    }

    @PostMapping("/internal/dev/ownership/cabinet")
    public int cabinet(@RequestBody Map<String, Object> body) {
        return ownership.reassignCabinet(str(body.get("cabinetNo")), str(body.get("agentNo")));
    }

    @GetMapping("/internal/dev/ownership/exists")
    public boolean exists(@RequestParam String cabinetNo) {
        return ownership.existsInScope(cabinetNo);
    }

    /** 空串还原为 null —— 远程侧用空串代替 null 过 JSON，这里还原语义。 */
    private static String str(Object v) {
        String s = v == null ? null : String.valueOf(v);
        return (s == null || s.isBlank()) ? null : s;
    }
}
