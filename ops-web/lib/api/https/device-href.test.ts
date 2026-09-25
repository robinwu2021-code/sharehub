import { describe, it, expect } from "vitest";
import { toOpsHref } from "./device";

/**
 * 上线门禁 `fixHref` 的路由翻译。实测后端给的是路径式（`/devices/CAB1000?tab=qc`、`/sites/ST300?tab=survey`），
 * 运营端是静态导出、详情一律 query 式 —— 不翻译的话门禁每条「去处理」都是 404，
 * 而门禁存在的意义就是告诉人去哪处理。
 */
describe("toOpsHref", () => {
  it("设备路径 → 设备详情页，页签保留；?edit=1 落到概览", () => {
    expect(toOpsHref("/devices/CAB1000?tab=qc")).toBe("/devices/detail?no=CAB1000&tab=qc");
    expect(toOpsHref("/devices/CAB1000")).toBe("/devices/detail?no=CAB1000");
    expect(toOpsHref("/devices/CAB1000?edit=1")).toBe("/devices/detail?no=CAB1000&tab=overview");
  });

  it("站点路径 → 运营管理的站点抽屉；没有站点号时去站点列表", () => {
    expect(toOpsHref("/sites/ST300?tab=survey")).toBe("/operation/sites?no=ST300&tab=survey");
    expect(toOpsHref("/sites/ST300")).toBe("/operation/sites?no=ST300");
    expect(toOpsHref("/sites")).toBe("/operation/sites");
  });

  it("工单列表补上列表视图；已是运营端路由的原样返回", () => {
    expect(toOpsHref("/work-orders?type=INSTALL&cabinetNo=CAB1")).toBe("/work-orders?type=INSTALL&cabinetNo=CAB1&view=list");
    expect(toOpsHref("/devices/detail?no=CAB1&tab=trial")).toBe("/devices/detail?no=CAB1&tab=trial");
    expect(toOpsHref("/venues?tab=contracts&siteNo=ST300")).toBe("/venues?tab=contracts&siteNo=ST300");
    expect(toOpsHref("/pricing")).toBe("/pricing");
    expect(toOpsHref(null)).toBeNull();
  });
});
