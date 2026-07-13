// 真实后端实现（CApi 契约）。端点对齐 docs/api §六（/mp）+ §五（/mp/trade）。
// 注意：C 端 BFF（/mp）尚未落地，真实模式下需后端补齐相应端点方可全绿（见 TDD-cend-bff）。
import { client } from "./http-client";
import type { CApi } from "./contract";
import type { OrderQ, PageQ } from "./types";

export const httpApi: CApi = {
  login: (req) => client.post("/mp/user/login", req),
  getProfile: () => client.get("/mp/user/profile"),
  getWallet: () => client.get("/mp/user/wallet"),

  listNearby: (q?: PageQ) => client.get("/mp/nearby/cabinets", q),

  rent: (cabinetNo) => client.post("/mp/trade/orders/rent", { cabinetNo }),
  getOrder: (no) => client.get(`/mp/trade/orders/${no}`),
  listOrders: (q?: OrderQ) => client.get("/mp/trade/orders", q),
  returnOrder: (no) => client.post(`/internal/trade/orders/${no}/return`, {}),

  report: (req) => client.post("/mp/user/report", req),
};
