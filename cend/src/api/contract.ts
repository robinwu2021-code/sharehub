// 单一 C 端 API 契约。mock 与真实后端各实现一份，页面只依赖此接口，
// 切换靠 api/index.ts 一处开关（消除散落 if(USE_MOCK)，对齐 ops-web 心智）。
import type {
  LoginReq, LoginResp, Profile, Wallet, NearbyCabinet, RentOrder, OrderQ, ReportReq, PageData, PageQ,
} from "./types";

export interface CApi {
  // 账户
  login(req: LoginReq): Promise<LoginResp>;
  getProfile(): Promise<Profile>;
  // 钱包
  getWallet(): Promise<Wallet>;
  // 附近网点
  listNearby(q?: PageQ): Promise<PageData<NearbyCabinet>>;
  // 借还
  rent(cabinetNo: string): Promise<{ orderNo: string }>;
  getOrder(orderNo: string): Promise<RentOrder>;
  listOrders(q?: OrderQ): Promise<PageData<RentOrder>>;
  returnOrder(orderNo: string): Promise<RentOrder>; // 骨架：模拟归还结算（真实由设备事件驱动）
  // 报障
  report(req: ReportReq): Promise<{ ok: true; reportNo: string }>;
}
