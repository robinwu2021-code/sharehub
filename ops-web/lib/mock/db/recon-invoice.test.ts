// 对账差错处理 / 发票开具作废（S2）：两套状态机的守卫测试。
//
// 这两块从前都只读或只能增改，`finance:recon:handle` 与 `finance:invoice:issue`
// 两个权限码定义了从没被用过。补上写操作后，四件事必须由服务端（本 db 层）兜住：
//  ① 差错处置有状态机，终态与已平批次不可再动；② 处置必填结论，且**汇总当场同步**（真落库）；
//  ③ 发票 DRAFT→ISSUED→VOID 强制，草稿不可直接作废、开具后抬头/金额锁死；
//  ④ 作废原因必填，开票金额必须与来源结算单对得上。
import { describe, expect, it } from "vitest";
import {
  reconciles, handleRecon, getReconStats, ReconError,
  invoices, saveInvoice, issueInvoice, voidInvoice, InvoiceError,
  settlements,
} from "./finance";
import { RECON_TERMINAL } from "../../types";
import type { Reconcile, Invoice } from "../../types";

// mock 是模块级可变状态、又没有 reset API，而处置动作是**单向**的（终态不可回退）。
// 所以用例不能靠「种子里刚好还剩一条 OPEN」，各自把要用的行摆回起始状态再跑——
// 这是测试自己的 fixture，不是给业务代码开的后门（业务侧只能走 handleRecon / issueInvoice）。
/** 取第 n 条差错批次并摆回「待处理」。不同用例用不同下标，避免互相踩。 */
function openRecon(nth = 0): Reconcile {
  const r = reconciles.filter((x) => x.status === "DIFF")[nth];
  Object.assign(r, { handleStatus: "OPEN", handleResult: null, handleNote: null, handledBy: null, handledAt: null });
  return r;
}
const invoiceWith = (status: Invoice["status"]) => invoices.find((i) => i.status === status)!;
/** 已确认/已打款的结算单：只有这种能开票。 */
const issuableSettlement = () => settlements.find((s) => s.status !== "DRAFT")!;
/** 新登记一张金额与来源单对得上的草稿票（每次都是新号，用例之间互不影响）。 */
function draftInvoice(): Invoice {
  const src = issuableSettlement();
  return saveInvoice({
    payeeName: src.payeeName, amount: src.totalAmount,
    vatTrn: "100777777777777", currency: src.currency, sourceNo: src.settleNo,
  });
}

describe("对账差错处理：状态机 + 汇总同源", () => {
  it("种子里三档处置进度都有（页面一进来四种去向都看得到）", () => {
    const statuses = new Set(reconciles.filter((r) => r.status === "DIFF").map((r) => r.handleStatus));
    expect(statuses).toContain("OPEN");
    expect(statuses).toContain("HANDLING");
    expect(statuses).toContain("RESOLVED");
    // 已平批次没有处置进度
    expect(reconciles.filter((r) => r.status === "MATCHED").every((r) => r.handleStatus === null)).toBe(true);
  });

  it("跑批结果与处置进度是两列：处置不篡改 status（MATCHED/DIFF 是机器算的事实）", () => {
    const r = openRecon(0);
    expect(r.status).toBe("DIFF");
    handleRecon(r.batchNo, "platform", "平台侧漏记一笔押金退回，凭证 V2044 待补");
    expect(r.status).toBe("DIFF"); // 事实不动
    expect(r.handleStatus).toBe("HANDLING"); // 进度动
  });

  it("标记渠道侧差错 → HANDLING，记定责/结论/处理人/处理时间", () => {
    const r = openRecon(1);
    const done = handleRecon(r.batchNo, "channel", "已提 nearpay 差错工单 NP-2026-0501", "Sara Ahmed");

    expect(done.handleStatus).toBe("HANDLING");
    expect(done.handleResult).toBe("CHANNEL_ERROR");
    expect(done.handleNote).toBe("已提 nearpay 差错工单 NP-2026-0501");
    expect(done.handledBy).toBe("Sara Ahmed");
    expect(done.handledAt).toBeTruthy();
  });

  it("已核对无误 → IGNORED（终态）；发起补差 → RESOLVED（终态）", () => {
    const a = openRecon(0);
    expect(handleRecon(a.batchNo, "verify", "逐笔核对无误，跨日切分次日已冲平").handleStatus).toBe("IGNORED");

    const b = openRecon(1);
    const resolved = handleRecon(b.batchNo, "compensate", "已发起补差单 ADJ-2026-0099");
    expect(resolved.handleStatus).toBe("RESOLVED");
    expect(resolved.handleResult).toBe("COMPENSATED");
    expect(RECON_TERMINAL).toContain(resolved.handleStatus!);
  });

  it("处理后汇总当场同步：未结笔数 −1、未结金额减掉这笔（真落库，不是只改单条）", () => {
    const r = openRecon(2);
    const before = getReconStats();

    handleRecon(r.batchNo, "verify", "核对无误，结案");

    const after = getReconStats();
    expect(after.diffCount).toBe(before.diffCount - 1);
    expect(after.openCount).toBe(before.openCount - 1);
    expect(after.diffAmount).toBe(Number((before.diffAmount - Math.abs(r.diff)).toFixed(2)));
    expect(after.closedCount).toBe(before.closedCount + 1);
    expect(after.closedAmount).toBe(Number((before.closedAmount + Math.abs(r.diff)).toFixed(2)));
    // 批次总数与已平批次不因人工处置而变
    expect(after.batchCount).toBe(before.batchCount);
    expect(after.matchedCount).toBe(before.matchedCount);
  });

  it("定责为中间态时仍算未结：标记平台侧只是转「处理中」，未结笔数不降", () => {
    const r = openRecon(3);
    const before = getReconStats();
    handleRecon(r.batchNo, "platform", "平台侧记账错漏，补记账中");
    const after = getReconStats();

    expect(after.diffCount).toBe(before.diffCount); // 还没结案
    expect(after.openCount).toBe(before.openCount - 1);
    expect(after.handlingCount).toBe(before.handlingCount + 1);
    expect(after.diffAmount).toBe(before.diffAmount);
  });

  it("非法迁移被拒：终态不可再处置，处理中不可再重复定责", () => {
    const r = openRecon(0);
    handleRecon(r.batchNo, "compensate", "补差完成");
    expect(() => handleRecon(r.batchNo, "verify", "想翻案")).toThrow(ReconError);
    expect(() => handleRecon(r.batchNo, "verify", "想翻案")).toThrow(/不允许执行/);

    const h = handleRecon(openRecon(1).batchNo, "channel", "挂起等回执");
    expect(() => handleRecon(h.batchNo, "platform", "改判平台侧")).toThrow(/不允许执行/);
    // 但处理中允许结案（verify / compensate 的 from 含 HANDLING）
    expect(handleRecon(h.batchNo, "compensate", "渠道回执已到，按差额补差结案").handleStatus).toBe("RESOLVED");
  });

  it("已平账批次没有可处置对象；不存在的批次直接报错", () => {
    const matched = reconciles.find((x) => x.status === "MATCHED")!;
    expect(matched.handleStatus).toBeNull();
    expect(() => handleRecon(matched.batchNo, "verify", "无差错")).toThrow(/已平账/);
    expect(() => handleRecon("RC99999", "verify", "x")).toThrow(/不存在/);
  });

  it("处理结论必填（空串/纯空格都算没填），拒绝后状态不变", () => {
    const r = openRecon(2);
    expect(() => handleRecon(r.batchNo, "verify", "")).toThrow(/结论必填/);
    expect(() => handleRecon(r.batchNo, "verify", "   ")).toThrow(/结论必填/);
    expect(r.handleStatus).toBe("OPEN");
    expect(r.handledBy).toBeNull();
  });
});

describe("发票：开具 / 作废状态机", () => {
  it("种子发票金额与其来源结算单一致（发票金额不自造）", () => {
    for (const inv of invoices) {
      const src = settlements.find((s) => s.settleNo === inv.sourceNo)!;
      expect(src, `发票 ${inv.invoiceNo} 的来源单 ${inv.sourceNo}`).toBeTruthy();
      expect(inv.amount).toBe(src.totalAmount);
    }
  });

  it("开具：DRAFT → ISSUED，生成发票代码/号码并记开具人与时间", () => {
    const draft = draftInvoice();
    expect(draft.invoiceCode).toBeNull();

    const done = issueInvoice(draft.invoiceNo, "Omar Khan");

    expect(done.status).toBe("ISSUED");
    expect(done.invoiceCode).toMatch(/^\d{10}$/);
    expect(done.invoiceNumber).toMatch(/^\d{8}$/);
    expect(done.issuedBy).toBe("Omar Khan");
    expect(done.issuedAt).toBeTruthy();
    // 号码不撞：新号大于所有既有号码
    expect(invoices.filter((x) => x.invoiceNumber === done.invoiceNumber)).toHaveLength(1);
  });

  it("已开具的票不能再开；已作废的票也不能再开", () => {
    const inv = issueInvoice(draftInvoice().invoiceNo);
    expect(() => issueInvoice(inv.invoiceNo)).toThrow(InvoiceError);
    expect(() => issueInvoice(inv.invoiceNo)).toThrow(/不允许执行/);

    const voided = invoiceWith("VOID");
    expect(() => issueInvoice(voided.invoiceNo)).toThrow(/不允许执行/);
    expect(() => issueInvoice("INV99999")).toThrow(/不存在/);
  });

  it("开票金额与来源结算单对不上 → 拒绝开具（票一开金额就锁死）", () => {
    const draft = draftInvoice();
    const good = draft.amount;
    draft.amount = good + 100; // 模拟来源单被重算后金额脱钩
    expect(() => issueInvoice(draft.invoiceNo)).toThrow(/对不上/);
    expect(draft.status).toBe("DRAFT"); // 拒绝后不留半开状态
    expect(draft.invoiceCode).toBeNull();
    draft.amount = good;
    expect(issueInvoice(draft.invoiceNo).status).toBe("ISSUED"); // 对平后就能开
  });

  it("作废：只能从 ISSUED 走，草稿不可直接作废", () => {
    const draft = draftInvoice();
    expect(() => voidInvoice(draft.invoiceNo, "写错了")).toThrow(/不允许执行/);
    expect(() => voidInvoice(draft.invoiceNo, "写错了")).toThrow(/草稿/);

    const issued = issueInvoice(draftInvoice().invoiceNo);
    const done = voidInvoice(issued.invoiceNo, "抬头填错，需重开", "Sara Ahmed");
    expect(done.status).toBe("VOID");
    expect(done.voidReason).toBe("抬头填错，需重开");
    expect(done.voidedBy).toBe("Sara Ahmed");
    expect(done.voidedAt).toBeTruthy();
    // 作废是终态：不能再作废一次
    expect(() => voidInvoice(done.invoiceNo, "再来一次")).toThrow(/不允许执行/);
  });

  it("作废原因必填（空串/纯空格都算没填），拒绝后状态不变", () => {
    const issued = issueInvoice(draftInvoice().invoiceNo);
    expect(() => voidInvoice(issued.invoiceNo)).toThrow(/作废原因必填/);
    expect(() => voidInvoice(issued.invoiceNo, "   ")).toThrow(/作废原因必填/);
    expect(issued.status).toBe("ISSUED");
    expect(issued.voidedAt).toBeNull();
  });
});

describe("发票：开具后抬头与金额不可再改", () => {
  it("已开具的票再存 → 拒绝，原值一字不动", () => {
    const inv = issueInvoice(draftInvoice().invoiceNo);
    const { payeeName, amount } = inv;

    expect(() => saveInvoice({ invoiceNo: inv.invoiceNo, payeeName: "改个抬头", amount: 1, sourceNo: inv.sourceNo }))
      .toThrow(InvoiceError);
    expect(() => saveInvoice({ invoiceNo: inv.invoiceNo, amount: 1, sourceNo: inv.sourceNo }))
      .toThrow(/已开具.*不可再改/);
    expect(inv.payeeName).toBe(payeeName);
    expect(inv.amount).toBe(amount);
  });

  it("已作废的票同样不可改（历史记录）", () => {
    const voided = invoiceWith("VOID");
    expect(() => saveInvoice({ invoiceNo: voided.invoiceNo, amount: 1, sourceNo: voided.sourceNo }))
      .toThrow(/已作废.*不可再改/);
  });

  it("草稿可改；表单送来的状态/票号/留痕字段一律丢弃（只能走开具/作废动作写）", () => {
    const src = issuableSettlement();
    const draft = saveInvoice({
      payeeName: src.payeeName, amount: src.totalAmount, vatTrn: "100888888888888",
      currency: src.currency, sourceNo: src.settleNo,
      // 以下都是「表单想偷改」的字段
      status: "ISSUED", invoiceCode: "0000000000", invoiceNumber: "99999999",
      issuedAt: "2026-01-01T00:00:00.000Z", issuedBy: "hacker", voidReason: "x",
    } as Partial<Invoice>);

    expect(draft.status).toBe("DRAFT");
    expect(draft.invoiceCode).toBeNull();
    expect(draft.invoiceNumber).toBeNull();
    expect(draft.issuedAt).toBeNull();
    expect(draft.issuedBy).toBeNull();
    expect(draft.voidReason).toBeNull();

    const edited = saveInvoice({ invoiceNo: draft.invoiceNo, payeeName: "改抬头OK", sourceNo: draft.sourceNo });
    expect(edited.payeeName).toBe("改抬头OK");
    expect(edited.status).toBe("DRAFT");
  });

  it("来源结算单必填、必须存在、且必须已确认（草稿单还可能重算，不许开票）", () => {
    expect(() => saveInvoice({ payeeName: "X", amount: 1, sourceNo: "" })).toThrow(/来源结算单必填/);
    expect(() => saveInvoice({ payeeName: "X", amount: 1, sourceNo: "STL99999" })).toThrow(/不存在/);
    const draftStl = settlements.find((s) => s.status === "DRAFT");
    if (draftStl) {
      expect(() => saveInvoice({ payeeName: "X", amount: draftStl.totalAmount, sourceNo: draftStl.settleNo }))
        .toThrow(/尚未确认/);
    }
  });
});
