package ai.neargo.opskit.svc.dev;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地联调用的测试身份提供方（21/07 O4-0）：一把 RSA 密钥，JWKS 由服务自己暴露，前端不必等宿主的内省端点。
 *
 * <p>密钥存在系统临时目录，重启后令牌仍然有效；删掉那个文件即换钥。<b>只在 local 配置档下存在</b>，生产镜像不装配它。
 */
public class DevIdentityProvider {

    public static final String ISSUER = "opskit-dev";
    private static final Logger log = LoggerFactory.getLogger(DevIdentityProvider.class);

    private final RSAKey key;
    private final Clock clock;

    public DevIdentityProvider(Path keyFile, Clock clock) {
        this.key = loadOrCreate(keyFile);
        this.clock = clock;
    }

    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    /**
     * @param sub    成员的宿主编号（IdP 映射为 member）
     * @param tenant 伙伴租户号（映射为 workspace；与路径里的 ext:{租户号} 必须一致）
     */
    public String sign(String sub, String name, String tenant, List<String> perms, List<String> scopes, long ttlMinutes) {
        long now = clock.millis();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(ISSUER).subject(sub).claim("tenant", tenant)
                .issueTime(new Date(now)).expirationTime(new Date(now + ttlMinutes * 60_000L));
        if (name != null) {
            c.claim("name", name);
        }
        if (perms != null && !perms.isEmpty()) {
            c.claim("perms", perms);
        }
        if (scopes != null && !scopes.isEmpty()) {
            c.claim("scopes", scopes);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), c.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("dev token signing failed", e);
        }
    }

    private static RSAKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return RSAKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
            RSAKey k = new RSAKeyGenerator(2048).keyID("opskit-dev-1").generate();
            Files.writeString(file, k.toJSONString(), StandardCharsets.UTF_8);
            log.info("生成本地联调签名密钥 file={}", file);
            return k;
        } catch (IOException | ParseException | JOSEException e) {
            throw new IllegalStateException("cannot load or create dev idp key at " + file, e);
        }
    }
}
