package ai.neargo.sharehub.platform.file.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 本地目录存储：单元 / 集成测试与离线开发。
 *
 * <p>限时地址由应用自己签、自己验（{@code /api/platform/files/raw/{fileNo}?exp=&sig=}），
 * 让测试走通与生产 COS <b>同一条</b>「302 到限时地址」路径，而不是另开一个「本地直接吐字节」的后门。
 */
@Component
@ConditionalOnProperty(name = "sharehub.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStoragePort {

    private final Path root;
    private final byte[] signSecret;

    public LocalFileStorage(@Value("${sharehub.storage.local.root:${java.io.tmpdir}/sharehub-files}") String root,
                            @Value("${sharehub.storage.local.sign-secret:dev-only-local-file-sign}") String signSecret) {
        this.root = Path.of(root);
        this.signSecret = signSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String type() {
        return "LOCAL";
    }

    @Override
    public String bucketOf(String bucketKind) {
        return "local-" + bucketKind.toLowerCase();
    }

    @Override
    public void put(String bucket, String key, Path file, String contentType) {
        try {
            Path dest = resolve(bucket, key);
            Files.createDirectories(dest.getParent());
            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储写入失败 key=" + key, e);
        }
    }

    @Override
    public String presignGet(StoredObject obj, Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        return "/api/platform/files/raw/" + obj.fileNo() + "?exp=" + exp + "&sig=" + sign(obj.fileNo(), exp);
    }

    /** 校验本地限时地址：签名正确且未过期。 */
    public boolean verify(String fileNo, long exp, String sig) {
        if (exp < Instant.now().getEpochSecond() || sig == null) return false;
        return MessageDigest.isEqual(sign(fileNo, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream open(String bucket, String key) {
        try {
            return Files.newInputStream(resolve(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储读取失败 key=" + key, e);
        }
    }

    @Override
    public void remove(String bucket, String key) {
        try {
            Files.deleteIfExists(resolve(bucket, key));
        } catch (IOException e) {
            throw new UncheckedIOException("本地存储删除失败 key=" + key, e);
        }
    }

    private Path resolve(String bucket, String key) {
        Path p = root.resolve(bucket).resolve(key).normalize();
        if (!p.startsWith(root)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", "objectKey");
        return p;
    }

    private String sign(String fileNo, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((fileNo + ":" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new java.security.ProviderException("HmacSHA256 不可用", e);
        }
    }
}
