import { describe, it, expect, beforeEach } from "vitest";
import * as we from "./workorder-ext";
import { workOrders } from "./workorder";
import { alarmRecords } from "./alarm";
import { sites } from "./location";
import { employees } from "./org";
import type { WorkOrder, AlarmRecord } from "../../types";

/**
 * 工单增强。
 *
 * <p>最要紧的一条：**详情要带出关联告警**。修完不看告警就关单的话，
 * 那几条会继续躺在告警中心而现场其实已经好了 ——
 * 告警数长期虚高，最后没人再信它。
 */

let woNo: string;
let cabinetNo: string;

beforeEach(() => {
  we.__resetTimelines();
  const n = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  woNo = `WO-T${n}`;
  cabinetNo = `CAB-T${n}`;
  workOrders.unshift({
    woNo, type: "FAULT", source: "MANUAL", priority: "HIGH", cabinetNo,
    locationName: "测试点位", status: "CREATED", assigneeName: null,
    slaDueAt: null, description: "柜门打不开", createdAt: new Date().toISOString(),
  } as unknown as WorkOrder);
});

const cur = () => workOrders.find((w) => w.woNo === woNo)!;

describe("详情", () => {
  it("★ 带出该设备上未关闭的告警——修完不看它们就关单，告警会长期虚高", () => {
    alarmRecords.unshift({
      alarmNo: `ALM-T${Date.now()}`, alarmCode: "E001", cabinetNo,
      level: "WARN", status: "OPEN", source: "DEVICE", occurredAt: new Date().toISOString(),
    } as unknown as AlarmRecord);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.alarms.length).toBeGreaterThan(0);
    expect(d.alarms.every((a) => a.cabinetNo === cabinetNo && a.status !== "CLOSED")).toBe(true);
  });

  it("★ 时间线为空时造一条建单记录——空时间线会让人以为「这单没人动过」", () => {
    const d = we.getWorkOrderDetail(woNo);
    expect(d.timeline.length).toBe(1);
    expect(d.timeline[0].kind).toBe("CREATE");
  });

  it("照片挂在步骤上——否则「修之前还是修之后拍的」永远说不清", () => {
    we.woTimelineAppend(woNo, "HANDLE", "现场处理", "换了锁扣", ["F-nope"]);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.timeline[0].fileNos).toEqual(["F-nope"]);
    // 文件不存在时不该塞个空壳进 photos（那会让界面渲染出一个坏图）
    expect(d.photos).toEqual([]);
  });

  it("不存在的工单要报错", () => {
    expect(() => we.getWorkOrderDetail("WO-nope")).toThrowError(/不存在|not found/i);
  });
});

describe("派单候选人", () => {
  it("★ 站点运维责任人排最前——此前是写死的常量，运营得自己记哪个站归谁", () => {
    const site = sites.find((s) => !s.archivedAt)!;
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    site.opsEmployeeNo = emp.employeeNo;
    const list = we.assigneeCandidates(site.siteNo);
    expect(list[0].no).toBe(emp.employeeNo);
    expect(list[0].siteOwner).toBe(true);
  });

  it("不传站点时没人是责任人，但顺序仍然稳定（顺序不稳很容易点错人）", () => {
    const a = we.assigneeCandidates().map((x) => x.no);
    const b = we.assigneeCandidates().map((x) => x.no);
    expect(a).toEqual(b);
    expect(we.assigneeCandidates().every((x) => !x.siteOwner)).toBe(true);
  });

  it("只给在职员工——停用的人不该出现在派单列表里", () => {
    const nos = new Set(we.assigneeCandidates().map((x) => x.no));
    for (const e of employees) {
      if (e.status !== "ACTIVE") expect(nos.has(e.employeeNo), `${e.employeeNo} 已停用却可被派单`).toBe(false);
    }
  });
});

describe("派生子单", () => {
  it("★ 另开一张而不是塞进备注——塞备注的话那个问题不进任何人的待办、也不被 SLA 计时", () => {
    const child = we.deriveWorkOrder(woNo, { type: "REFILL", description: "顺带发现缺宝" });
    expect(child.woNo).not.toBe(woNo);
    expect(child.status).toBe("CREATED");
    expect(child.sourceNo).toBe(woNo);
    expect(child.description).toBe("顺带发现缺宝");
  });

  it("描述必填——没有描述的派生单，接手的人不知道要修什么", () => {
    expect(() => we.deriveWorkOrder(woNo, { type: "REFILL", description: "  " }))
      .toThrowError(/写清|Description/i);
  });

  it("父子两边都留痕", () => {
    const child = we.deriveWorkOrder(woNo, { type: "REFILL", description: "缺宝" });
    expect(we.getWorkOrderDetail(woNo).timeline.some((t) => t.kind === "DERIVE")).toBe(true);
    expect(we.getWorkOrderDetail(child.woNo).timeline.some((t) => t.kind === "CREATE")).toBe(true);
  });
});

describe("接管", () => {
  it("★ 原因必填——被接管的人要看得到为什么换人", () => {
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    expect(() => we.takeoverWorkOrder(woNo, { employeeNo: emp.employeeNo, reason: " " }))
      .toThrowError(/原因|reason/i);
  });

  it("接管后责任人变更并留痕（从谁到谁都记下来）", () => {
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    we.takeoverWorkOrder(woNo, { employeeNo: emp.employeeNo, reason: "原负责人休假" });
    expect(cur().assigneeName).toBe(emp.name);
    const t = we.getWorkOrderDetail(woNo).timeline.find((x) => x.kind === "TAKEOVER")!;
    expect(t.note).toContain(emp.name);
    expect(t.note).toContain("原负责人休假");
  });

  it("终态工单不能接管；不存在的员工要拒", () => {
    cur().status = "CLOSED";
    expect(() => we.takeoverWorkOrder(woNo, { employeeNo: "E001", reason: "x" })).toThrowError(/不能接管|Cannot take over/);
    cur().status = "CREATED";
    expect(() => we.takeoverWorkOrder(woNo, { employeeNo: "E-nope", reason: "x" })).toThrowError(/员工不存在|Employee not found/);
  });
});

describe("摘要条", () => {
  it("四个数都是「要人动手的事」，不放工单总数", () => {
    const s = we.woSummary();
    expect(Object.keys(s).sort()).toEqual(["dueSoon", "overdue", "reviewFailed", "toDispatch"]);
  });

  it("★ 已超时算的是未关闭且过了 SLA 的——关掉的单不该再催", () => {
    cur().slaDueAt = new Date(Date.now() - 3600_000).toISOString();
    const before = we.woSummary().overdue;
    expect(before).toBeGreaterThan(0);
    cur().status = "CLOSED";
    expect(we.woSummary().overdue).toBe(before - 1);
  });

  it("待派单 = CREATED", () => {
    const before = we.woSummary().toDispatch;
    cur().status = "DISPATCHED";
    expect(we.woSummary().toDispatch).toBe(before - 1);
  });
});
