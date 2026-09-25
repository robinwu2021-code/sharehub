import { describe, it, expect, beforeEach } from "vitest";
import * as we from "./workorder-ext";
import { workOrders } from "./workorder";
import { alarmRecords } from "./alarm";
import { sites } from "./location";
import { employees } from "./org";
import { agents } from "./agent";
import { uploadFile } from "./file";
import { iso } from "./internal";
import type { WorkOrder, AlarmRecord, WorkOrderType, WorkOrderStatus } from "../../types";

/**
 * 工单增强（批次 7a 数据层 + 7b 对齐真后端）。每条校验都照后端 WoOpsServiceImpl 抄 ——
 * mock 放行而后端拒绝，离线调一路顺、切后端当场 400。
 *
 * <p>最要紧的一条：**详情要带出关联告警**。修完不看告警就关单的话，
 * 那几条会继续躺在告警中心而现场其实已经好了 —— 告警数长期虚高，最后没人再信它。
 */

let woNo: string;
let cabinetNo: string;

/** mock 的「现在」是 iso(0)；SLA 时限都相对它造。 */
const hoursAgo = (h: number) => iso(h * 3600_000);

function seed(p: Partial<WorkOrder> = {}): WorkOrder {
  const n = `${Date.now()}${Math.floor(Math.random() * 100000)}`;
  const w = {
    woNo: `WO-T${n}`, type: "FAULT" as WorkOrderType, source: "MANUAL", priority: "HIGH", cabinetNo: `CAB-T${n}`,
    locationName: "测试点位", status: "CREATED" as WorkOrderStatus, assigneeName: null,
    slaDueAt: null, description: "柜门打不开", createdAt: iso(0), ...p,
  } as unknown as WorkOrder;
  workOrders.unshift(w);
  return w;
}
const photo = () => uploadFile(new File(["x"], "site.jpg", { type: "image/jpeg" }), "WO_PHOTO").fileNo;

beforeEach(() => {
  we.__resetTimelines();
  const w = seed();
  woNo = w.woNo;
  cabinetNo = w.cabinetNo!;
});

const cur = () => workOrders.find((w) => w.woNo === woNo)!;

describe("详情", () => {
  it("★ 带出关联告警（告警上回填了本工单号的）——与后端 byWorkOrder 同口径", () => {
    const alarmNo = `ALM-T${Date.now()}`;
    alarmRecords.unshift({
      alarmNo, alarmCode: "E001", cabinetNo, level: "WARN", status: "OPEN", source: "DEVICE",
      occurredAt: new Date().toISOString(), workOrderNo: woNo,
    } as unknown as AlarmRecord);
    // 同柜但没挂到本单的告警不算：否则一台柜上两张单会互相「认领」对方的告警
    alarmRecords.unshift({
      alarmNo: `${alarmNo}-other`, alarmCode: "E002", cabinetNo, level: "WARN", status: "OPEN", source: "DEVICE",
      occurredAt: new Date().toISOString(), workOrderNo: null,
    } as unknown as AlarmRecord);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.alarms.map((a) => a.alarmNo)).toEqual([alarmNo]);
    expect(d.order.ops?.alarmCount).toBe(1);
  });

  it("★ 时间线为空时造一条建单记录——空时间线会让人以为「这单没人动过」", () => {
    const d = we.getWorkOrderDetail(woNo);
    expect(d.timeline.length).toBe(1);
    expect(d.timeline[0].kind).toBe("CREATE");
  });

  it("照片挂在步骤上——否则「修之前还是修之后拍的」永远说不清", () => {
    we.woTimelineAppend(woNo, "HANDLE", "HANDLE", "换了锁扣", ["F-nope"]);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.timeline[0].fileNos).toEqual(["F-nope"]);
    // 文件不存在时不该塞个空壳进 photos（那会让界面渲染出一个坏图）
    expect(d.photos).toEqual([]);
  });

  it("每个动作都留痕：派单 → 接单 → 处理，时间线按发生顺序", () => {
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    we.dispatchWorkOrderExt(woNo, emp.employeeNo);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.timeline.map((t) => t.action)).toEqual(["DISPATCH"]);
    // 同后端：人工派单不写受理人类型（已报后端缺口，见 dispatchWorkOrderExt）
    expect(d.order.ops?.assigneeType).toBeNull();
  });

  it("不存在的工单要报错", () => {
    expect(() => we.getWorkOrderDetail("WO-nope")).toThrowError(/不存在|not found/i);
  });
});

describe("列表运营维度", () => {
  it("★ 行带 ops：SLA 剩余分钟（超时为负）、站点、关联告警数", () => {
    cur().slaDueAt = hoursAgo(2);
    const row = we.listWorkOrdersRich({ keyword: woNo }).list[0];
    expect(row.ops?.slaRemainMinutes).toBe(-120);
    expect(row.ops?.alarmCount).toBe(0);
  });

  it("slaState：OVERDUE 只收未完工且过了时限的；DUE_SOON 是两小时内", () => {
    cur().slaDueAt = hoursAgo(1);
    expect(we.listWorkOrdersRich({ keyword: woNo, slaState: "OVERDUE" }).total).toBe(1);
    expect(we.listWorkOrdersRich({ keyword: woNo, slaState: "DUE_SOON" }).total).toBe(0);
    cur().slaDueAt = hoursAgo(-1);
    expect(we.listWorkOrdersRich({ keyword: woNo, slaState: "DUE_SOON" }).total).toBe(1);
    cur().slaDueAt = hoursAgo(1);
    cur().status = "CLOSED";
    expect(we.listWorkOrdersRich({ keyword: woNo, slaState: "OVERDUE" }).total).toBe(0);
  });

  it("status 可逗号多值（后端 in 查询）；priority / source 精确匹配", () => {
    expect(we.listWorkOrdersRich({ keyword: woNo, status: "DONE,CREATED" }).total).toBe(1);
    expect(we.listWorkOrdersRich({ keyword: woNo, priority: "LOW" }).total).toBe(0);
    expect(we.listWorkOrdersRich({ keyword: woNo, source: "MANUAL" }).total).toBe(1);
  });
});

describe("派单候选人", () => {
  it("★ 站点运维责任人排最前——此前是写死的常量，运营得自己记哪个站归谁", () => {
    const site = sites.find((s) => !s.archivedAt)!;
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    site.opsEmployeeNo = emp.employeeNo;
    (site as { operateAgentNo?: string | null }).operateAgentNo = null;
    const list = we.assigneeCandidates(site.siteNo);
    expect(list[0].no).toBe(emp.employeeNo);
    expect(list[0].siteOwner).toBe(true);
  });

  it("站点由代理运营且代理启用中：代理就是责任人（后端 resolveOwner 先看代理）", () => {
    const site = sites.find((s) => !s.archivedAt)!;
    const ag = agents.find((a) => a.status === "ENABLED")!;
    (site as { operateAgentNo?: string | null }).operateAgentNo = ag.agentNo;
    const list = we.assigneeCandidates(site.siteNo);
    expect(list[0]).toMatchObject({ type: "AGENT", no: ag.agentNo, siteOwner: true });
    (site as { operateAgentNo?: string | null }).operateAgentNo = null;
  });

  it("不传站点时没人是责任人，但顺序仍然稳定（顺序不稳很容易点错人）", () => {
    const a = we.assigneeCandidates().map((x) => x.no);
    const b = we.assigneeCandidates().map((x) => x.no);
    expect(a).toEqual(b);
    expect(we.assigneeCandidates().every((x) => !x.siteOwner)).toBe(true);
  });

  it("只给在职的运维（OPS）员工——停用的、客服财务不该出现在派单列表里", () => {
    const nos = new Set(we.assigneeCandidates().map((x) => x.no));
    for (const e of employees) {
      const ok = e.status === "ACTIVE" && (e.roleNos ?? [e.roleNo]).includes("OPS");
      expect(nos.has(e.employeeNo), `${e.employeeNo} 可派性不对`).toBe(ok);
    }
  });
});

describe("完工收紧（按类型必填）", () => {
  const toProcessing = (p: Partial<WorkOrder> = {}) => {
    Object.assign(cur(), { status: "PROCESSING", assigneeName: "E101" }, p);
  };

  it("★ 维修单不传照片 → 拒；不给故障原因 → 拒", () => {
    toProcessing();
    expect(() => we.completeWorkOrderExt(woNo, { handleNote: "修好了", faultReasonCode: "LOCK" }))
      .toThrowError(/照片|photo/i);
    expect(() => we.completeWorkOrderExt(woNo, { handleNote: "修好了", fileNos: [photo()] }))
      .toThrowError(/故障原因|fault reason/i);
    expect(cur().status).toBe("PROCESSING");
  });

  it("撤机单必须填清点数（与在柜数比对的唯一依据）", () => {
    toProcessing({ type: "REMOVE" });
    expect(() => we.completeWorkOrderExt(woNo, { handleNote: "撤了", fileNos: [photo()] }))
      .toThrowError(/清点|counted/i);
    expect(we.completeWorkOrderExt(woNo, { handleNote: "撤了", fileNos: [photo()], countedQty: 6 }).status).toBe("DONE");
  });

  it("补宝单不强制照片（一刀切只会让人随手拍张地板交差）", () => {
    toProcessing({ type: "REFILL" });
    expect(we.completeWorkOrderExt(woNo, { handleNote: "补了 8 个" }).status).toBe("DONE");
  });

  it("状态不对先报状态，不报「缺照片」", () => {
    expect(() => we.completeWorkOrderExt(woNo, { handleNote: "x" })).toThrowError(/不允许|完成/);
  });

  it("★ 完工落照片（绑定）、故障原因、成本；照片进详情，时间线的完工那步带着它们", () => {
    toProcessing();
    const f = photo();
    we.completeWorkOrderExt(woNo, { handleNote: "换锁", faultReasonCode: "LOCK", fileNos: [f], partChanged: true, partCost: 30, laborCost: 20 });
    const d = we.getWorkOrderDetail(woNo);
    expect(d.order.status).toBe("DONE");
    expect(d.order.ops?.faultReasonCode).toBe("LOCK");
    expect(d.order.partsReplaced).toBe("PART_CHANGED");
    expect(d.photos.map((p) => p.fileNo)).toEqual([f]);
    expect(d.photos[0].status).toBe("BOUND");
    const step = d.timeline.find((t) => t.action === "COMPLETE")!;
    expect(step.fileNos).toEqual([f]);
    expect(step.faultReasonCode).toBe("LOCK");
  });

  it("成本不能为负", () => {
    toProcessing({ type: "REFILL" });
    expect(() => we.completeWorkOrderExt(woNo, { handleNote: "x", laborCost: -1 })).toThrowError(/负|negative/i);
  });
});

describe("完工复核 · 验收", () => {
  const linkAlarm = (status: AlarmRecord["status"]) => {
    const alarmNo = `ALM-R${Date.now()}${Math.random()}`;
    alarmRecords.unshift({ alarmNo, alarmCode: "E1", cabinetNo, level: "WARN", status, source: "DEVICE",
      occurredAt: iso(0), workOrderNo: woNo } as unknown as AlarmRecord);
    return alarmNo;
  };
  const complete = () => {
    Object.assign(cur(), { status: "PROCESSING", source: "ALERT", type: "REFILL" });
    return we.completeWorkOrderExt(woNo, { handleNote: "修复" });
  };

  it("★ 关联告警全部已恢复 → 系统自动验收关单（SYSTEM）", () => {
    linkAlarm("CLOSED");
    complete();
    expect(cur().status).toBe("CLOSED");
    expect(cur().auditorName).toBe("SYSTEM");
    expect(we.getWorkOrderDetail(woNo).order.ops?.reviewStatus).toBe("PASSED");
  });

  it("★ 仍有告警未恢复 → 复核未通过，停在 DONE，进摘要条「复核未通过」", () => {
    const before = we.woSummary().reviewFailed;
    linkAlarm("OPEN");
    complete();
    expect(cur().status).toBe("DONE");
    expect(we.getWorkOrderDetail(woNo).order.ops?.reviewStatus).toBe("FAILED");
    expect(we.woSummary().reviewFailed).toBe(before + 1);
    expect(we.listWorkOrdersRich({ keyword: woNo, reviewStatus: "FAILED" }).total).toBe(1);
  });

  it("★ 复核未通过还要放行：必须写明理由；写了才关得掉", () => {
    linkAlarm("OPEN");
    complete();
    expect(() => we.closeWorkOrderExt(woNo, { auditResult: "PASS" })).toThrowError(/放行理由|Explain/);
    expect(we.closeWorkOrderExt(woNo, { auditResult: "PASS", auditNote: "设备延迟上报，现场复测正常" }).status).toBe("CLOSED");
  });

  it("验收结论 FAIL 不许关单（那是返工的活）", () => {
    Object.assign(cur(), { status: "DONE" });
    expect(() => we.closeWorkOrderExt(woNo, { auditResult: "FAIL" })).toThrowError(/返工|rework/i);
  });
});

describe("抢单池", () => {
  it("池里只有待派单（CREATED）", () => {
    expect(we.listWoPool({ size: 500 }).list.every((w) => w.status === "CREATED")).toBe(true);
    expect(we.listWoPool({ size: 500 }).list.some((w) => w.woNo === woNo)).toBe(true);
  });

  it("★ 抢单 = 派给自己 + 接单（落 ACCEPTED），抢过的单不再在池里", () => {
    const w = we.grabWorkOrder(woNo);
    expect(w.status).toBe("ACCEPTED");
    expect(w.assigneeName).toBe(we.MOCK_ME);
    expect(we.listWoPool({ size: 500 }).list.some((x) => x.woNo === woNo)).toBe(false);
    expect(we.getWorkOrderDetail(woNo).timeline.map((t) => t.action)).toEqual(expect.arrayContaining(["DISPATCH", "ACCEPT"]));
  });

  it("第二个人再抢 → 已不在池中（与后端条件更新失败同一个结果）", () => {
    we.grabWorkOrder(woNo);
    expect(() => we.grabWorkOrder(woNo)).toThrowError(/不在抢单池|no longer/);
  });
});

describe("巡检派生", () => {
  const onSite = () => Object.assign(cur(), { type: "INSPECT", status: "PROCESSING" });

  it("★ 另开一张而不是塞进备注——来源挂巡检单号，进待派单", () => {
    onSite();
    const child = we.deriveWorkOrder(woNo, { type: "REFILL", description: "顺带发现缺宝" });
    expect(child.woNo).not.toBe(woNo);
    expect(child.status).toBe("CREATED");
    expect(child.source).toBe("INSPECTION");
    expect(child.sourceNo).toBe(`${woNo}:REFILL:${cabinetNo}`);
    expect(child.description).toContain("顺带发现缺宝");
    expect(child.priority).toBe("MEDIUM");
  });

  it("只有巡检单、且巡检员已到场才能派生（后端 derive_inspect_only / derive_not_on_site）", () => {
    expect(() => we.deriveWorkOrder(woNo, { type: "REFILL", description: "x" })).toThrowError(/巡检单/);
    Object.assign(cur(), { type: "INSPECT", status: "CREATED" });
    expect(() => we.deriveWorkOrder(woNo, { type: "REFILL", description: "x" })).toThrowError(/到场/);
  });

  it("描述必填；类型只能是维修 / 补宝 / 清洁", () => {
    onSite();
    expect(() => we.deriveWorkOrder(woNo, { type: "REFILL", description: "  " })).toThrowError(/写清|Description/i);
    expect(() => we.deriveWorkOrder(woNo, { type: "INSTALL", description: "x" })).toThrowError(/只能派生|Invalid type/);
  });

  it("父子两边都留痕", () => {
    onSite();
    const child = we.deriveWorkOrder(woNo, { type: "CLEAN", description: "柜面脏" });
    expect(we.getWorkOrderDetail(woNo).timeline.some((t) => t.note?.includes(child.woNo))).toBe(true);
    expect(we.getWorkOrderDetail(child.woNo).timeline.some((t) => t.kind === "CREATE")).toBe(true);
  });
});

describe("平台接管", () => {
  const agentOverdue = () => {
    const ag = agents.find((a) => a.status === "ENABLED")!;
    we.dispatchWorkOrderExt(woNo, ag.agentNo);
    we.__setAssigneeType(woNo, "AGENT");   // 模拟后端自动派单按站点责任人（代理）派出
    cur().slaDueAt = hoursAgo(3);
    return ag;
  };

  it("★ 代理承接且 SLA 已超时 → 改派平台员工，留痕写明从谁到谁、为什么", () => {
    const ag = agentOverdue();
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    const w = we.takeoverWorkOrder(woNo, { employeeNo: emp.employeeNo, reason: "超时 3 小时未到场" });
    expect(w.status).toBe("DISPATCHED");
    expect(w.assigneeName).toBe(emp.employeeNo);
    const d = we.getWorkOrderDetail(woNo);
    expect(d.order.ops?.assigneeType).toBe("EMPLOYEE");
    const t = d.timeline.find((x) => x.action === "TAKEOVER")!;
    expect(t.note).toContain(ag.agentNo);
    expect(t.note).toContain("超时 3 小时未到场");
  });

  it("★ SLA 未超时 → 拒（后端 takeover_not_breached）", () => {
    agentOverdue();
    cur().slaDueAt = hoursAgo(-1);
    expect(() => we.takeoverWorkOrder(woNo, { reason: "x" })).toThrowError(/尚未超时|not breached/);
  });

  it("平台员工的单不能接管（直接驳回重派就是）", () => {
    const emp = employees.find((e) => e.status === "ACTIVE")!;
    we.dispatchWorkOrderExt(woNo, emp.employeeNo);
    cur().slaDueAt = hoursAgo(3);
    expect(() => we.takeoverWorkOrder(woNo, {})).toThrowError(/代理承接|agent-assigned/);
    we.__setAssigneeType(woNo, "EMPLOYEE");
    expect(() => we.takeoverWorkOrder(woNo, {})).toThrowError(/代理承接|agent-assigned/);
  });

  it("不指定接管人 → 自动选在职运维；指定了停用员工 → 拒", () => {
    agentOverdue();
    const left = employees.find((e) => e.status !== "ACTIVE")!;
    expect(() => we.takeoverWorkOrder(woNo, { employeeNo: left.employeeNo })).toThrowError(/停用|inactive/);
    const w = we.takeoverWorkOrder(woNo, {});
    expect(employees.some((e) => e.employeeNo === w.assigneeName && e.status === "ACTIVE")).toBe(true);
  });
});

describe("摘要条", () => {
  it("四个数都是「要人动手的事」，不放工单总数", () => {
    const s = we.woSummary();
    expect(Object.keys(s).sort()).toEqual(["dueSoon", "overdue", "reviewFailed", "toDispatch"]);
  });

  it("★ 已超时算的是未关闭且过了 SLA 的——关掉的单不该再催", () => {
    cur().slaDueAt = hoursAgo(1);
    const before = we.woSummary().overdue;
    expect(before).toBeGreaterThan(0);
    cur().status = "CLOSED";
    expect(we.woSummary().overdue).toBe(before - 1);
  });

  it("即将超时 = 两小时内到期（与后端同口径，不是四小时）", () => {
    cur().slaDueAt = hoursAgo(-3);
    const before = we.woSummary().dueSoon;
    cur().slaDueAt = hoursAgo(-1);
    expect(we.woSummary().dueSoon).toBe(before + 1);
  });

  it("待派单 = CREATED", () => {
    const before = we.woSummary().toDispatch;
    cur().status = "DISPATCHED";
    expect(we.woSummary().toDispatch).toBe(before - 1);
  });
});

describe("成本汇总", () => {
  it("★ 完工填的成本按承担方聚合：平台员工的单记站点", () => {
    Object.assign(cur(), { status: "PROCESSING", type: "REFILL", assigneeName: "E101" });
    we.completeWorkOrderExt(woNo, { handleNote: "补宝", laborCost: 12.5 });
    const rows = we.listWoCosts({ bearerType: "SITE" });
    expect(rows.every((r) => r.bearerType === "SITE")).toBe(true);
    expect(rows.reduce((n, r) => n + r.orders, 0)).toBeGreaterThan(0);
  });

  it("代理运维的单记代理（代理自己的运维成本）", () => {
    const ag = agents.find((a) => a.status === "ENABLED")!;
    we.dispatchWorkOrderExt(woNo, ag.agentNo);
    we.__setAssigneeType(woNo, "AGENT");
    Object.assign(cur(), { status: "PROCESSING", type: "REFILL" });
    const before = we.listWoCosts({ bearerType: "AGENT" }).find((r) => r.bearerNo === ag.agentNo)?.total ?? 0;
    we.completeWorkOrderExt(woNo, { handleNote: "补宝", partCost: 10, laborCost: 5 });
    const after = we.listWoCosts({ bearerType: "AGENT" }).find((r) => r.bearerNo === ag.agentNo)!;
    expect(after.total).toBe(before + 15);
  });

  it("按金额降序；日期区间左闭右开", () => {
    const rows = we.listWoCosts();
    for (let i = 1; i < rows.length; i++) expect(rows[i - 1].total).toBeGreaterThanOrEqual(rows[i].total);
    expect(we.listWoCosts({ from: "2099-01-01" })).toEqual([]);
  });
});
