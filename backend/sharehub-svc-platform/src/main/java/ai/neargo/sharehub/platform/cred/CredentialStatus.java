package ai.neargo.sharehub.platform.cred;

import java.util.Arrays;
import java.util.Optional;

/**
 * 凭据启用与否（{@code cred_credential.status}，词表见 V114 列注释 {@code ACTIVE/DISABLED}）。
 *
 * <h3>为什么两个值也值得收</h3>
 * 这个词表管的是**认证**：{@code CredentialServiceImpl.verify} 判「不是 ACTIVE 就不放行」。
 * 判据里打错一个字母不报错 —— 写成永不匹配是**全员登不进来**，
 * 写反是**停用的凭据照样能登**。后者没有任何症状，直到有人拿离职同事的口令登进来。
 */
public enum CredentialStatus {

    /** 可用。 */
    ACTIVE,
    /** 已停用 —— 口令正确也不放行（离职、疑似泄露）。 */
    DISABLED;

    public static Optional<CredentialStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
