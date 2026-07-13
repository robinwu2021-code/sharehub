// 推送端能力：App APNs/FCM/UniPush（自主）/ 小程序订阅消息（模板受限）。MVP 占位，接入时补。
export function initPush() {
  // #ifdef APP-PLUS
  // uni.getPushClientId({ success: (r) => reportDeviceToken(r.cid) }) // 接入 UniPush/FCM 时启用
  // #endif
}
