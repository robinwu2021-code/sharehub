// 域间共享的**私有**常量与格式化工具（原 db.ts 中未导出的模块级常量）。
// 覆盖：厂商码 / 点位名 / 场地方名 / 取模取值 p / 时间轴 iso / 操作人 / 手机号 / 昵称 / 白名单用途。
// 注意：本文件不经 index.ts 对外导出——公开 API 与拆分前保持完全一致。
import type { WhitelistReason } from "../../types";

export const VENDORS = ["cd-tech", "sd-power", "chargenow"];
export const LOCS = ["Dubai Mall L1", "Mall of Emirates", "DXB T3", "Marina Walk", "City Centre Deira", "Yas Mall", "Ibn Battuta"];
export const VENUE_NAMES = ["Emaar Malls", "Majid Al Futtaim", "DXB Airports", "Aldar", "Nakheel"];
export const p = <T,>(a: T[], i: number) => a[i % a.length];
export const iso = (offsetMs: number) => new Date(Date.UTC(2026, 6, 11, 12, 0, 0) - offsetMs).toISOString();

export const OPERATORS = ["admin", "Ali Hassan", "Omar Khan", "Sara Ahmed", "Wang Lei"];
export const phone = (i: number, prefix = "+9715") => `${prefix}${String(1000000 + i * 173).slice(0, 7)}`;

export const NICKS = ["Ahmed", "Mohammed", "Fatima", "Layla", "Yusuf", "李明", "Noura", "Khalid"];

// 免费订单来源 / 免费白名单用途共用同一套枚举（原 db.ts 中的 REASONS）
export const REASONS: WhitelistReason[] = ["INTERNAL_TEST", "VIP", "BD_DEMO", "MERCHANT_SELF"];
