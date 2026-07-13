package ai.neargo.powerbank.user.consumer;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 手机号 OTP 登录（App + H5 普通浏览器，MENA 主路径）。 */
@Component
public class PhoneOtpStrategy implements ConsumerLoginStrategy {

    private final OtpService otp;

    public PhoneOtpStrategy(OtpService otp) {
        this.otp = otp;
    }

    @Override
    public String grantType() {
        return "phone_otp";
    }

    @Override
    public ResolvedIdentity authenticate(ConsumerLoginReq req) {
        if (req.phone() == null || req.phone().isBlank()) {
            throw new IllegalArgumentException("手机号为空");
        }
        String phone = req.phone().trim();       // 生产：E.164 归一
        otp.verify(phone, req.otp());
        String uid = sha256(phone);              // 明文入 pb_pii；查找键用确定性哈希
        return new ResolvedIdentity(Provider.PHONE, uid, "PHONE:" + uid, phone, req.nickname(), req.avatar());
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
