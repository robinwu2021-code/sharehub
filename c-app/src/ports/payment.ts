// 支付端能力抽象（PaymentPort）。App：卡/Apple Pay/Google Pay；小程序：微信 JSAPI。
// MVP 全走后端 /mp（其内 PaymentPort Stub），端侧不写死任何 PSP；nearpay 接入后在此挂真实收银台 SDK。
import { api } from "@/api";
import type { PayParams, PayResult } from "@/api";

export interface PaymentPort {
  preAuthFreeDeposit(cabinetNo: string): Promise<{ authNo: string; frozen: number }>;
  pay(p: PayParams): Promise<PayResult>;
}

export const payment: PaymentPort = {
  // 免押 = 卡预授权冻结（经 nearpay），MVP Stub
  preAuthFreeDeposit: (cabinetNo) => api.depositFree(cabinetNo),
  // 收银台：MVP 由后端 Stub 直接返回成功；接 nearpay 后此处按 cashierParams 唤起端 SDK
  pay: (p) => api.pay(p),
};
