import { describe, it, expect } from "vitest";
import { exitFixHref } from "./agent-exit-href";

/** 清退门禁「去处理」：后端给的路径运营端不存在，原样渲染点进去是 404。 */
describe("exitFixHref", () => {
  it.each([
    ["/agents/AG001?tab=assign", "/agents?tab=assign"],
    ["/agents/AG001?tab=sites", "/operation/sites"],
    ["/work-orders?assignee=AG001", "/work-orders?view=list&assignee=AG001"],
    ["/finance/shares?payeeNo=AG001", "/finance?tab=records&payee=AG001"],
    ["/finance/settlements?payeeNo=AG001", "/finance?tab=settlements&payee=AG001"],
    ["/finance/withdrawals?payeeNo=AG001", "/finance?tab=withdrawals&payee=AG001"],
  ])("%s → %s", (from, to) => expect(exitFixHref(from)).toBe(to));

  it("已经是运营端路由的原样透传（后端修好之后这里不该再改写）", () => {
    expect(exitFixHref("/agents?tab=assign")).toBe("/agents?tab=assign");
    expect(exitFixHref("/devices/detail?no=CAB1")).toBe("/devices/detail?no=CAB1");
  });

  it("空值给 null（GateChecklist 据此不渲染链接）", () => {
    expect(exitFixHref(null)).toBeNull();
    expect(exitFixHref("")).toBeNull();
  });
});
