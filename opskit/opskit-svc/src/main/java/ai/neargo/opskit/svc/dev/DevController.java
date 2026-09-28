package ai.neargo.opskit.svc.dev;

import ai.neargo.opskit.commons.error.CommonErrors;
import ai.neargo.opskit.commons.error.OpsKitException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地联调端点（仅 local 配置档）：JWKS、按人设签令牌、种子清单。
 * 用法见 opskit/scripts/dev-token.sh。
 */
@Profile("local")
@RestController
@RequestMapping("/dev")
public class DevController {

    private final DevIdentityProvider idp;
    private final DevSeed seed;

    public DevController(DevIdentityProvider idp, DevSeed seed) {
        this.idp = idp;
        this.seed = seed;
    }

    /**
     * @param as    种子里的人设（alice / bob / frank / viewer …）；给了就用人设的 sub · 权限 · 标签
     * @param sub   不用人设时：成员的宿主编号（不存在即开通）
     */
    public record TokenRequest(String as, String sub, String name, List<String> perms, List<String> scopes, Long ttlMinutes) {
    }

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks() {
        return idp.jwks();
    }

    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody TokenRequest r) {
        DevSeed.Manifest m = seed.manifest();
        long ttl = r.ttlMinutes() == null ? 12 * 60 : r.ttlMinutes();
        if (r.as() != null) {
            DevSeed.Persona p = m.personas().get(r.as());
            if (p == null) {
                throw new OpsKitException(CommonErrors.NOT_FOUND, "unknown persona " + r.as() + "; known: " + m.personas().keySet());
            }
            return Map.of("token", idp.sign(p.externalRef(), p.displayName(), m.tenant(), p.tokenPerms(), p.tokenScopes(), ttl), "persona", p);
        }
        if (r.sub() == null || r.sub().isBlank()) {
            throw new OpsKitException(CommonErrors.VALIDATION, "give 'as' (persona) or 'sub'");
        }
        return Map.of("token", idp.sign(r.sub(), r.name(), m.tenant(), r.perms(), r.scopes(), ttl));
    }

    /** 种子写完前返回 503（健康检查在种子写完前就会是 200，别以它为准）。 */
    @GetMapping("/seed")
    public org.springframework.http.ResponseEntity<Object> seed() {
        if (!seed.ready()) {
            return org.springframework.http.ResponseEntity.status(503).body(Map.of("error", "opskit.dev.seed_not_ready", "message", "seed is being written"));
        }
        return org.springframework.http.ResponseEntity.ok(seed.manifest());
    }
}
