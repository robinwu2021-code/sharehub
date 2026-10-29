import { describe, it, expect } from "vitest";
import { lifecycleStage, lifecyclePhase, nextStep } from "./device-lifecycle";

const base = { status: "IN_STOCK" as const, locationNo: null, warehouseNo: "WH01", qcStatus: "PASSED" as const, issuedAgentNo: null };

describe("设备生命周期环节（展示态派生）", () => {
  it("在仓：待质检 / 可出库", () => {
    expect(lifecycleStage({ ...base, qcStatus: "PENDING" })).toBe("PENDING_QC");
    expect(lifecycleStage({ ...base, qcStatus: "FAILED" })).toBe("PENDING_QC");
    expect(lifecycleStage(base)).toBe("IN_STOCK");
    // 存量免检（qcStatus 为空）视同放行
    expect(lifecycleStage({ ...base, qcStatus: null })).toBe("IN_STOCK");
  });
  it("出库单 → 绑定 → 上线", () => {
    expect(lifecycleStage({ ...base, warehouseNo: null, issuedAgentNo: "AG1" })).toBe("ISSUED");
    expect(lifecycleStage({ ...base, warehouseNo: null, issuedAgentNo: "AG1", locationNo: "L1" })).toBe("BOUND");
    expect(lifecycleStage({ ...base, status: "DEPLOYED", locationNo: "L1" })).toBe("LIVE");
  });
  it("在途 / 故障 / 报废看存储态", () => {
    expect(lifecycleStage({ ...base, status: "IN_TRANSIT" })).toBe("IN_TRANSIT");
    expect(lifecycleStage({ ...base, status: "FAULT", locationNo: "L1" })).toBe("FAULT");
    expect(lifecycleStage({ ...base, status: "RETIRED" })).toBe("RETIRED");
  });
  it("环节映射到进度条的大阶段；每个环节都有下一步", () => {
    expect(lifecyclePhase("PENDING_QC")).toBe("INBOUND");
    expect(lifecyclePhase("ISSUED")).toBe("DEPLOY");
    expect(lifecyclePhase("BOUND")).toBe("INSTALL");
    expect(lifecyclePhase("FAULT")).toBe("MAINTAIN");
    expect(nextStep({ ...base, warehouseNo: null, issuedAgentNo: "AG1" }).href).toBe("/inventory?tab=issued");
    expect(nextStep({ ...base, locationNo: "L1", issuedAgentNo: "AG1" }).who).toBe("运维");
  });
});
