package ai.neargo.powerbank.user.consumer;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机 OTP（MVP 内存版；生产换 auth-core OTP + 短信网关）。
 * dev 固定码 {@code 000000} 便于联调；发码后单次校验失效。防刷（频控/图形验证）留待生产。
 */
@Service
public class OtpService {

    static final String DEV_MASTER = "000000";
    private final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();

    /** 发码（dev 固定 000000），返回码用于联调展示。 */
    public String issue(String phone) {
        codes.put(phone, DEV_MASTER);
        return DEV_MASTER;
    }

    /** 校验；失败抛 IllegalArgumentException（→400）。 */
    public void verify(String phone, String otp) {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException("验证码为空");
        }
        String stored = codes.get(phone);
        if (!otp.equals(DEV_MASTER) && !otp.equals(stored)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        codes.remove(phone);
    }
}
