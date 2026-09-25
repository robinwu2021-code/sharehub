package ai.neargo.sharehub.platform.file.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * 腾讯云 COS（2026-09-25 定：统一经应用服务器上传；单一地域，与应用同区走内网）。
 *
 * <p><b>为什么不用 COS SDK</b>：构建离线进行，SDK 的传递依赖不在本地仓库；而本类只需要
 * PutObject / GetObject / DeleteObject / 预签名 GET 四个动作，用 JDK HttpClient 加 COS 的
 * 请求签名（q-sign-algorithm=sha1）即可，不引入任何依赖。签名算法见 COS 文档「请求签名」。
 *
 * <p>启动自检：type=cos 而桶名或密钥为空 → <b>启动失败</b>，不带着空配置跑到第一次上传才报错。
 * 密钥只走环境变量，<b>不进日志</b>；日志只记 fileNo 与对象键，不记限时地址（地址里带签名）。
 */
@Component
@ConditionalOnProperty(name = "sharehub.storage.type", havingValue = "cos")
public class CosFileStorage implements FileStoragePort {

    private static final Logger log = LoggerFactory.getLogger(CosFileStorage.class);
    private static final Duration SIGN_TTL = Duration.ofMinutes(10);

    private final String region;
    private final String secretId;
    private final String secretKey;
    private final Map<String, String> buckets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CosFileStorage(@Value("${sharehub.storage.cos.region:}") String region,
                          @Value("${sharehub.storage.cos.secret-id:}") String secretId,
                          @Value("${sharehub.storage.cos.secret-key:}") String secretKey,
                          @Value("${sharehub.storage.cos.bucket-private:}") String bucketPrivate,
                          @Value("${sharehub.storage.cos.bucket-pii:}") String bucketPii,
                          @Value("${sharehub.storage.cos.bucket-public:}") String bucketPublic) {
        this.region = region;
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.buckets = Map.of("PRIVATE", bucketPrivate, "PII", bucketPii, "PUBLIC", bucketPublic);
    }

    @PostConstruct
    void selfCheck() {
        StringJoiner missing = new StringJoiner(", ");
        if (region.isBlank()) missing.add("sharehub.storage.cos.region");
        if (secretId.isBlank()) missing.add("sharehub.storage.cos.secret-id");
        if (secretKey.isBlank()) missing.add("sharehub.storage.cos.secret-key");
        buckets.forEach((k, v) -> { if (v.isBlank()) missing.add("sharehub.storage.cos.bucket-" + k.toLowerCase()); });
        if (missing.length() > 0) {
            throw new java.security.ProviderException("COS 存储配置缺失：" + missing + "（运维按部署清单补齐环境变量）");
        }
    }

    @Override
    public String type() {
        return "COS";
    }

    @Override
    public String bucketOf(String bucketKind) {
        return buckets.get(bucketKind);
    }

    @Override
    public void put(String bucket, String key, Path file, String contentType) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(bucket, key, ""))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", authorization("put", "/" + key, Map.of(), Map.of("host", host(bucket))))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("COS 写入失败 key={} status={}：检查桶权限与子账号策略", key, resp.statusCode());
                throw new UncheckedIOException(new IOException("COS PutObject " + resp.statusCode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("COS 写入失败 key=" + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("COS 写入被中断 key=" + key, e));
        }
    }

    @Override
    public String presignGet(StoredObject obj, Duration ttl) {
        String auth = signature("get", "/" + obj.key(), Map.of(), Map.of(), ttl);
        return "https://" + host(obj.bucket()) + "/" + encodePath(obj.key()) + "?" + auth;
    }

    @Override
    public InputStream open(String bucket, String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(bucket, key, ""))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", authorization("get", "/" + key, Map.of(), Map.of("host", host(bucket))))
                    .GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 404) throw new UncheckedIOException(new FileNotFoundException(key));
            if (resp.statusCode() / 100 != 2) throw new UncheckedIOException(new IOException("COS GetObject " + resp.statusCode()));
            return resp.body();
        } catch (IOException e) {
            throw new UncheckedIOException("COS 读取失败 key=" + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("COS 读取被中断 key=" + key, e));
        }
    }

    @Override
    public void remove(String bucket, String key) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri(bucket, key, ""))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authorization("delete", "/" + key, Map.of(), Map.of("host", host(bucket))))
                    .DELETE().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2 && resp.statusCode() != 404) {
                throw new UncheckedIOException(new IOException("COS DeleteObject " + resp.statusCode()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("COS 删除失败 key=" + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("COS 删除被中断 key=" + key, e));
        }
    }

    // —— 签名（COS 文档「请求签名」）——

    private String authorization(String method, String path, Map<String, String> params, Map<String, String> headers) {
        return signature(method, path, params, headers, SIGN_TTL);
    }

    String signature(String method, String path, Map<String, String> params, Map<String, String> headers, Duration ttl) {
        long start = Instant.now().getEpochSecond() - 60;
        long end = start + 60 + ttl.getSeconds();
        String keyTime = start + ";" + end;
        String signKey = hmacSha1Hex(secretKey.getBytes(StandardCharsets.UTF_8), keyTime);
        String[] p = canonical(params);
        String[] h = canonical(headers);
        String httpString = method.toLowerCase() + "\n" + path + "\n" + p[1] + "\n" + h[1] + "\n";
        String stringToSign = "sha1\n" + keyTime + "\n" + sha1Hex(httpString) + "\n";
        String sig = hmacSha1Hex(signKey.getBytes(StandardCharsets.UTF_8), stringToSign);
        return "q-sign-algorithm=sha1&q-ak=" + enc(secretId) + "&q-sign-time=" + enc(keyTime) + "&q-key-time=" + enc(keyTime)
                + "&q-header-list=" + enc(h[0]) + "&q-url-param-list=" + enc(p[0]) + "&q-signature=" + sig;
    }

    /** → [键列表（;分隔）, 键=值（&分隔）]，键小写、按字典序、值 URL 编码。 */
    private static String[] canonical(Map<String, String> m) {
        TreeMap<String, String> t = new TreeMap<>();
        m.forEach((k, v) -> t.put(enc(k.toLowerCase()), enc(v)));
        return new String[]{String.join(";", t.keySet()),
                String.join("&", t.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList())};
    }

    private URI uri(String bucket, String key, String query) {
        return URI.create("https://" + host(bucket) + "/" + encodePath(key) + (query.isEmpty() ? "" : "?" + query));
    }

    private String host(String bucket) {
        return bucket + ".cos." + region + ".myqcloud.com";
    }

    private static String encodePath(String key) {
        return String.join("/", java.util.Arrays.stream(key.split("/")).map(CosFileStorage::enc).toList());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String sha1Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new java.security.ProviderException("SHA-1 不可用", e);
        }
    }

    private static String hmacSha1Hex(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new java.security.ProviderException("HmacSHA1 不可用", e);
        }
    }
}
