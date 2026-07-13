// 登录端能力：只有「拿登录凭据」因端而异（条件编译）。产出统一 LoginParams 交给 api.login。
// App：手机号 OTP（MVP）/ Apple / Google（后续）；小程序：微信 openid。
import type { LoginParams } from "@/api";

export async function acquireLogin(input: { phone?: string; otp?: string } = {}): Promise<LoginParams> {
  let params: LoginParams = { grantType: "phone_otp", phone: input.phone, otp: input.otp };
  // #ifdef MP-WEIXIN
  params = { grantType: "wechat_miniapp", code: await wxLogin() };
  // #endif
  return params;
}

// #ifdef MP-WEIXIN
function wxLogin(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.login({
      provider: "weixin",
      success: (r) => resolve(r.code),
      fail: (e) => reject(new Error(e.errMsg || "wx login failed")),
    });
  });
}
// #endif
