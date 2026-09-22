import { describe, it, expect } from "vitest";
import {
  formatMarketTime, formatMarketTimeWithZone, marketLocalToUtcIso, tzOffsetMinutes,
  formatOffset, marketNowLocal,
} from "./market-time";

describe("市场时区：库存 UTC，界面按市场时区", () => {
  it("迪拜 = UTC+4，全年无夏令时", () => {
    expect(tzOffsetMinutes(new Date("2026-01-15T00:00:00Z"), "Asia/Dubai")).toBe(240);
    expect(tzOffsetMinutes(new Date("2026-07-15T00:00:00Z"), "Asia/Dubai")).toBe(240);
  });

  it("UTC → 迪拜显示", () => {
    expect(formatMarketTime("2026-09-22T16:00:00.000Z", "Asia/Dubai")).toBe("2026-09-22 20:00");
    // 跨日：UTC 22:30 在迪拜已是次日 02:30
    expect(formatMarketTime("2026-09-22T22:30:00Z", "Asia/Dubai")).toBe("2026-09-23 02:30");
  });

  it("带时区标注", () => {
    expect(formatMarketTimeWithZone("2026-09-22T16:00:00Z", "Asia/Dubai")).toBe("2026-09-22 20:00 (UTC+4)");
  });

  it("迪拜输入 → UTC 存储，且可逆", () => {
    expect(marketLocalToUtcIso("2026-09-22 20:00", "Asia/Dubai")).toBe("2026-09-22T16:00:00.000Z");
    expect(marketLocalToUtcIso("2026-09-23T02:30", "Asia/Dubai")).toBe("2026-09-22T22:30:00.000Z");
    const iso = marketLocalToUtcIso("2026-12-31 23:59", "Asia/Dubai");
    expect(formatMarketTime(iso, "Asia/Dubai")).toBe("2026-12-31 23:59");
  });

  it("有夏令时的时区也换算正确（为将来多市场兜底）", () => {
    // 伦敦夏令时 UTC+1，冬令时 UTC+0
    expect(marketLocalToUtcIso("2026-07-01 12:00", "Europe/London")).toBe("2026-07-01T11:00:00.000Z");
    expect(marketLocalToUtcIso("2026-01-01 12:00", "Europe/London")).toBe("2026-01-01T12:00:00.000Z");
  });

  it("偏移格式", () => {
    expect(formatOffset(240)).toBe("UTC+4");
    expect(formatOffset(330)).toBe("UTC+5:30");
    expect(formatOffset(-180)).toBe("UTC-3");
  });

  it("空值与非法值不抛错，返回空串；非法输入格式抛错", () => {
    expect(formatMarketTime(null)).toBe("");
    expect(formatMarketTime("not-a-date")).toBe("");
    expect(() => marketLocalToUtcIso("22/09/2026 20:00")).toThrow();
  });

  it("marketNowLocal 给 datetime-local 用的格式", () => {
    expect(marketNowLocal(new Date("2026-09-22T16:05:00Z"), "Asia/Dubai")).toBe("2026-09-22T20:05");
  });
});
