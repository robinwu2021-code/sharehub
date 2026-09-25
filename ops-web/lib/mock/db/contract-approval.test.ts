import { describe, it, expect, beforeEach } from "vitest";
import * as ca from "./contract-approval";
import { contracts } from "./location";
import type { Contract } from "../../types";

/**
 * 合同审批链：**mock 的状态机要和后端一样严**。
 *
 * <p>mock 放行而后端拒绝，离线调一路顺、切后端当场 500 —— 本仓库踩过四次。
 * 所以这里逐条验闸：谁能提交、谁能审、驳回要不要理由、终止是不是立刻生效。
 */

let no: string;

/** 每条用例自备一份草稿——共享种子会让用例之间互相污染（本仓库的既有教训）。 */
beforeEach(() => {
  no = `CT-T${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const draft = {
    contractNo: no, venueNo: "VEN300", siteNo: "ST300", venueName: "测试场地", siteName: "测试站点",
    shareRate: 0.2, entryFee: 0, startAt: "2026-01-01", endAt: "2099-12-31",
    status: "DRAFT", attachments: [],
  } as unknown as Contract;
  contracts.unshift(draft);
});

/** 跑到「已签批」那一步：提交 → 运营通过 → 财务通过。 */
function toSigned(): Contract {
  ca.submitContract(no);
  ca.auditContract(no, "APPROVE");
  return ca.cosignContract(no, "APPROVE");
}

describe("两段式审批", () => {
  it("★ 提交后进运营环节——状态是 PENDING，是谁的活由 auditStage 说", () => {
    const c = ca.submitContract(no);
    expect(c.status).toBe("PENDING");
    expect(c.flow?.auditStage).toBe("OPS");
  });

  it("★ 运营通过**不直接到 SIGNED**，而是转财务会签（状态仍是 PENDING）", () => {
    ca.submitContract(no);
    const c = ca.auditContract(no, "APPROVE");
    expect(c.status, "这一步就到 SIGNED 的话，财务那一关等于不存在").toBe("PENDING");
    expect(c.flow?.auditStage).toBe("FINANCE");
  });

  it("财务会签通过 → SIGNED", () => {
    expect(toSigned().status).toBe("SIGNED");
  });

  it("★ 财务不能越过运营先签——还在 OPS 环节时调会签要拒", () => {
    ca.submitContract(no);
    expect(() => ca.cosignContract(no, "APPROVE")).toThrowError(/财务会签环节|FINANCE audit stage/);
  });

  it("★ 运营环节过了就不能再审一次（幂等之外，重复审会覆盖审批人）", () => {
    ca.submitContract(no);
    ca.auditContract(no, "APPROVE");
    expect(() => ca.auditContract(no, "APPROVE")).toThrowError(/运营审批环节|OPS audit stage/);
  });

  it("★ 驳回必须写原因——不说理由，提交人只能猜", () => {
    ca.submitContract(no);
    expect(() => ca.auditContract(no, "REJECT")).toThrowError(/原因|reason/i);
    expect(() => ca.auditContract(no, "REJECT", "  ")).toThrowError(/原因|reason/i);
  });

  it("驳回退回 DRAFT，可以改完再提", () => {
    ca.submitContract(no);
    expect(ca.auditContract(no, "REJECT", "分成比例超授权").status).toBe("DRAFT");
    expect(ca.submitContract(no).status).toBe("PENDING");
  });

  it("撤回：审批中反悔走 withdraw，不是去驳回自己的单", () => {
    ca.submitContract(no);
    const c = ca.withdrawContract(no, "条款还要谈");
    expect(c.status).toBe("DRAFT");
    expect(c.flow?.auditStage).toBeNull();
  });

  it("★ DRAFT 不能直接审——没提交的单子不该出现在任何人的待办里", () => {
    expect(() => ca.auditContract(no, "APPROVE")).toThrowError(/运营审批环节|OPS audit stage/);
  });
});

describe("签署与终止", () => {
  it("只有 SIGNED 能登记签署件", () => {
    expect(() => ca.signContract(no, "2026-09-25", [])).toThrowError(/已签批|SIGNED/);
    toSigned();
    expect(ca.signContract(no, "2026-09-25", []).flow?.signedAt).toBe("2026-09-25");
  });

  it("★ 申请终止**不改合同状态**——审批期间照常生效，否则驳回后那几天的账没法补", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    const r = ca.terminateContract(no, "场地方要求提前撤场");
    expect(r.status, "提交申请就终止的话，审批还没做完合同已经停了").toBe("ACTIVE");
    expect(r.flow?.termination?.status).toBe("PENDING");
  });

  it("终止原因必填；不能同时挂两份待审申请", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    expect(() => ca.terminateContract(no, " ")).toThrowError(/原因|reason/i);
    ca.terminateContract(no, "第一份");
    expect(() => ca.terminateContract(no, "第二份")).toThrowError(/待审批|pending/i);
  });

  it("★ 终止获批且生效日是未来 → 合同还没终止（到点由定时任务推）", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    const future = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
    ca.terminateContract(no, "撤场", future);
    const r = ca.auditContractTermination(no, "APPROVE");
    expect(r.status, "一点就终止的话，离线看到的行为与线上不一致").toBe("ACTIVE");
    expect(r.flow?.termination?.status).toBe("APPROVED");
  });

  it("终止获批且没填生效日 → 立即终止", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    ca.terminateContract(no, "立即撤场");
    expect(ca.auditContractTermination(no, "APPROVE").status).toBe("TERMINATED");
  });
});

describe("续签与补充协议", () => {
  it("续签生成新草稿，指回原合同", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    const n = ca.renewContract(no);
    expect(n.status).toBe("DRAFT");
    expect(n.contractNo).not.toBe(no);
    expect(n.flow?.prevContractNo).toBe(no);
    expect(n.attachments, "新合同不该继承旧合同的扫描件").toEqual([]);
  });

  it("补充协议挂在主合同下，种类是 SUPPLEMENT", () => {
    const c = contracts.find((x) => x.contractNo === no)!;
    c.status = "ACTIVE";
    const sup = ca.supplementContract(no, "2026-10-01");
    expect(sup.flow?.contractKind).toBe("SUPPLEMENT");
    expect(sup.flow?.parentContractNo).toBe(no);
    expect(sup.startAt).toBe("2026-10-01");
  });

  it("DRAFT 不能续签也不能签补充协议", () => {
    expect(() => ca.renewContract(no)).toThrowError(/生效中或已到期|ACTIVE or EXPIRED/);
    expect(() => ca.supplementContract(no)).toThrowError(/生效中|ACTIVE/);
  });
});

describe("留痕与摘要", () => {
  it("★ 每一步都留痕，最新在前", () => {
    toSigned();
    const events = ca.listContractLogs(no).map((l) => l.event);
    expect(events[0]).toBe("COSIGN");
    expect(events).toContain("SUBMIT");
    expect(events).toContain("APPROVE");
  });

  it("摘要条的六个数都是「要人动手的事」，且能随状态变化", () => {
    const before = ca.contractSummary().pendingMine;
    ca.submitContract(no);
    expect(ca.contractSummary().pendingMine).toBe(before + 1);
    ca.auditContract(no, "APPROVE");
    expect(ca.contractSummary().pendingCosign).toBeGreaterThan(0);
  });
});
