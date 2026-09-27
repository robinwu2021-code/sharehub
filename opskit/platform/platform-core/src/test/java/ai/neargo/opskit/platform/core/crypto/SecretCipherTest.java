package ai.neargo.opskit.platform.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

public class SecretCipherTest {

    /** 测试专用密钥；生产密钥来自配置 opskit.crypto.key。 */
    public static SecretCipher cipher() {
        return new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void roundTripsWithAFreshIvEachTime() {
        SecretCipher c = cipher();
        String a = c.encrypt("oks_secret");
        String b = c.encrypt("oks_secret");
        assertThat(a).startsWith("v1:").isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("oks_secret");
    }

    @Test
    void tamperedOrForeignCiphertextIsRejected() {
        SecretCipher c = cipher();
        String enc = c.encrypt("oks_secret");
        byte[] raw = Base64.getDecoder().decode(enc.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        SecretCipher other = new SecretCipher(Base64.getEncoder().encodeToString(otherKey));
        assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesKeysOfTheWrongLength() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretCipher(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
