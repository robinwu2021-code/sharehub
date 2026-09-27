/**
 * 按**输入形状**判账号池。纯数字（可带 `+`、空格、连字符）且 7–15 位 → 手机号 → 合作伙伴。
 *
 * <p>合作伙伴只能用**手机号**：验证码要发到手机上，而库里存的是 hash + 掩码 ——
 * `phone_enc`/`email_enc` 都是空的，掩码不可逆，服务端根本拿不到明文去发信。
 * 所以必须由本人输入手机号，服务端规范化后按 hash 反查（见 AgentIdentityPort）。
 *
 * <p>要求 **7 位以上**而不是「只要是数字」：不然输入过程中的 `1`、`13` 会一路被判成
 * 手机号，提示行跟着抖，而用户其实在打员工号。
 */
export function looksLikePhone(v: string): boolean {
  const t = v.trim().replace(/[\s-]/g, "");
  return /^\+?\d{7,15}$/.test(t);
}
