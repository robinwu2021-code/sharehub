// 巡检计划「立即执行一次」单测（S7）。
//
// 背景：mock 没有定时器，「按计划自动开工单」原先是纯展示——计划改得再漂亮也不会有工单。
// 本文件钉住三件事：
//   ① 执行**真的落工单**（进 workOrders，来源 PLAN + sourceNo 挂计划号，且已派给计划负责人）
//   ② **幂等**：同计划同周期第二次执行被拒，且不多开任何一张工单（连点两下不重复派工）
//   ③ 守卫：停用计划 / 路线站点在台账里没有机柜 → 整批拒绝，不留半截数据
import { describe, it, expect } from "vitest";
import { inspectionPeriodKey, inspectionRunnable } from "../../types";
import { cabinets } from "./device";
import {
  workOrders, inspectionPlans, saveInspectionPlan,
  runInspectionPlanNow, InspectionRunError,
} from "./workorder";

/** 造一条可执行的计划：路线取台账里真实存在的两个站点名。 */
let seq = 0;
const stops = () => [cabinets[0].locationName!, cabinets[1].locationName!];
function fixture(over: Partial<Parameters<typeof saveInspectionPlan>[0]> = {}) {
  return saveInspectionPlan({
    route: stops().join(" → "), frequency: "每月", nextAt: "2026-08-01T09:00:00Z",
    assignee: `巡检员${++seq}`, active: true, ...over,
  });
}

describe("立即执行一次：真的生成工单", () => {
  it("每站一张 INSPECT 工单，来源 PLAN / sourceNo 挂计划号，并已派给计划负责人", () => {
    const plan = fixture();
    const before = workOrders.length;
    const r = runInspectionPlanNow(plan.planNo);

    expect(r.woNos).toHaveLength(2);
    expect(workOrders.length).toBe(before + 2);
    for (const no of r.woNos) {
      const w = workOrders.find((x) => x.woNo === no)!;
      expect(w.type).toBe("INSPECT");
      expect(w.source).toBe("PLAN");
      expect(w.sourceNo).toBe(plan.planNo);
      expect(w.status).toBe("DISPATCHED");        // 生成即派单，走的是状态机
      expect(w.assigneeName).toBe(plan.assignee);
      expect(w.expectedAt).toBe(plan.nextAt);
      expect(cabinets.some((c) => c.cabinetNo === w.cabinetNo)).toBe(true);
    }
  });

  it("执行留痕落在计划上（上次执行时间 / 周期键 / 工单号），但不推进 nextAt", () => {
    const plan = fixture();
    const nextAt = plan.nextAt;
    const r = runInspectionPlanNow(plan.planNo);
    expect(plan.lastRunAt).toBeTruthy();
    expect(plan.lastRunPeriod).toBe(inspectionPeriodKey(plan.frequency));
    expect(plan.lastRunWoNos).toEqual(r.woNos);
    expect(plan.nextAt).toBe(nextAt); // 手动补跑不代表排期走过
  });
});

describe("幂等：同周期不重复开单", () => {
  it("第二次执行被拒，且工单数与计划留痕都不变", () => {
    const plan = fixture();
    const first = runInspectionPlanNow(plan.planNo);
    const count = workOrders.length;

    expect(() => runInspectionPlanNow(plan.planNo)).toThrow(InspectionRunError);
    expect(workOrders.length).toBe(count);
    expect(plan.lastRunWoNos).toEqual(first.woNos);
  });

  it("按钮判定与服务端校验共用 inspectionRunnable（不会「按钮亮着点了报错」）", () => {
    const plan = fixture();
    expect(inspectionRunnable(plan)).toBeNull();
    runInspectionPlanNow(plan.planNo);
    expect(inspectionRunnable(plan)).toMatch(/已执行过/);
  });

  it("周期键随频率变粒度：每日按天、每月按月，未知频率退化到按天", () => {
    const at = new Date("2026-07-30T10:00:00Z");
    expect(inspectionPeriodKey("每日", at)).toBe("2026-07-30");
    expect(inspectionPeriodKey("每月", at)).toBe("2026-07");
    expect(inspectionPeriodKey("每周", at)).toMatch(/^2026-W\d{2}$/);
    expect(inspectionPeriodKey("双周", at)).toMatch(/^2026-B\d{2}$/);
    expect(inspectionPeriodKey("每两小时", at)).toBe("2026-07-30");
  });

  it("换个周期就能再执行（把上次执行周期挪走，模拟到了下个月）", () => {
    const plan = fixture();
    runInspectionPlanNow(plan.planNo);
    plan.lastRunPeriod = "1999-01"; // 夹具直接改，业务代码只能经 runInspectionPlanNow
    expect(runInspectionPlanNow(plan.planNo).woNos).toHaveLength(2);
  });
});

describe("守卫：整批拒绝，不做半成功", () => {
  it("停用的计划不能执行", () => {
    const plan = fixture({ active: false });
    expect(() => runInspectionPlanNow(plan.planNo)).toThrow(/已停用/);
    expect(plan.lastRunAt).toBeNull();
  });

  it("路线上任一站点在台账里没有机柜 → 一张都不开", () => {
    const plan = fixture({ route: `${stops()[0]} → 火星购物中心` });
    const before = workOrders.length;
    expect(() => runInspectionPlanNow(plan.planNo)).toThrow(/没有在册机柜/);
    expect(workOrders.length).toBe(before);
    expect(plan.lastRunPeriod).toBeNull();
  });

  it("路线为空 / 计划不存在 → 拒绝", () => {
    expect(() => runInspectionPlanNow(fixture({ route: "   " }).planNo)).toThrow(InspectionRunError);
    expect(() => runInspectionPlanNow("IP-NOT-EXIST")).toThrow(/不存在/);
  });

  it("增改计划不能伪造执行留痕（lastRun* 一律被剥离）", () => {
    const plan = fixture();
    saveInspectionPlan({ planNo: plan.planNo, lastRunPeriod: inspectionPeriodKey(plan.frequency), lastRunWoNos: ["WO-FAKE"] });
    expect(plan.lastRunPeriod).toBeNull();
    expect(plan.lastRunWoNos).toEqual([]);
    expect(inspectionRunnable(plan)).toBeNull(); // 伪造未生效，仍可执行
  });
});

describe("种子计划", () => {
  it("种子一律未执行过（页面进来按钮不会一半是灰的）", () => {
    for (const p of inspectionPlans.filter((x) => x.planNo.startsWith("IP2"))) {
      expect(p.lastRunPeriod ?? null).toBeNull();
    }
  });
});
