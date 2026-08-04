package ai.neargo.sharehub.dev.internal;

import ai.neargo.sharehub.api.core.dto.CabinetBrief;
import ai.neargo.sharehub.api.core.port.CabinetQueryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@link CabinetQueryPort} 的服务端（仅服务间调用）。
 *
 * <p>⚠️ 与 {@code OwnershipInternalController#exists} 同样的局限：
 * 两个方法都<b>带数据范围</b>，结果取决于调用方会话。
 * {@link ai.neargo.sharehub.api.remote.RemoteCaller} 已透传 Bearer token，
 * 但**后台任务没有身份**，那种上下文下调用本端点拿到的是无范围结果 —— 不要在对账器里用。
 */
@RestController
public class CabinetQueryInternalController {

    private final CabinetQueryPort query;

    public CabinetQueryInternalController(CabinetQueryPort query) {
        this.query = query;
    }

    @GetMapping("/internal/dev/cabinets/assignable")
    public List<CabinetBrief> assignable(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String agentNo,
                                         @RequestParam(required = false) Integer limit) {
        return query.assignable(keyword, agentNo, limit);
    }

    @PostMapping("/internal/dev/cabinets/briefs")
    public List<CabinetBrief> briefs(@RequestBody List<String> cabinetNos) {
        return query.briefsByNos(cabinetNos);
    }
}
