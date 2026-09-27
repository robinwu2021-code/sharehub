package ai.neargo.opskit.process.core.domain.definition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 版本 = 规范化 JSON 的 SHA-256 前 16 位（21/02 §6.3）。内容不变，版本就不变。 */
public final class DefinitionVersion {

    private DefinitionVersion() {
    }

    public static String of(String canonicalJson) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
