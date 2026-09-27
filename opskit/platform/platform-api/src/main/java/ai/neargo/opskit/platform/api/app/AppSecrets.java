package ai.neargo.opskit.platform.api.app;

/**
 * 只在签发 / 轮换时返回一次的明文。服务端只存凭证哈希与加密后的签名密钥，之后无法再取回。
 *
 * @param credential    调用系统面用的凭证（{@code Authorization: Bearer …}）
 * @param signingSecret 验证 webhook 签名用的密钥
 */
public record AppSecrets(AppView app, String credential, String signingSecret) {
}
