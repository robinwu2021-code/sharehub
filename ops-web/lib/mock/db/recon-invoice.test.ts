// 对账差错处理 / 发票开具作废（S2）：两套状态机的守卫测试。
//
// 这两块从前都只读或只能增改，`finance:recon:handle` 与 `finance:invoice:issue`
// 两个权限码定义了从没被用过。补上写操作后，四件事必须由服务端（本 db 层）兜住：
//  ① 差错处置有状态机，终态与已平批次不可再动；② 处置必填结论，且**汇总当场同步**（真落库）；
//  ③ 发票 DRAFT→ISSUED→VOID 强制，草稿不可直接作废、开具后抬头/金额锁死；
//  ④ 作废原因必填，开票金额必须与来源结算单对得上。
import { describe, expect, it } from "vitest";
import {
  reconciles, reconDiffs, listReconDiffs, handleRecon, getReconStats, ReconError,
  invoices, saveInvoice, issueInvoice, voidInvoice, InvoiceError,
  settlements,

  ledger, listVoucherEntries, voucherBalance, listLedgerInPeriod,
  createVoucher,} from "./finance";
import { RECON_TERMINAL, parseReconDiffDetail } from "../../types";
import type { Reconcile, ReconDiff, Invoice } from "../../types";

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

// 逐笔差额：detail 里两侧金额相减（页面上那一列也是这么算的，不是另存的字段）
const deltaOf = (d: ReconDiff) => {
  const v = parseReconDiffDetail(d.detail)!;
  return (v.nearpay ?? 0) - (v.ledger ?? 0);
};
const sum = (rows: ReconDiff[]) => Number(rows.reduce((s, d) => s + deltaOf(d), 0).toFixed(2));
/** 取第 n 条差错批次，连同它的差错行一起摆回「一笔都没平」的起始状态。 */
function openReconWithDiffs(nth = 0): { batch: Reconcile; diffs: ReconDiff[] } {
  const batch = openRecon(nth);
  const diffs = reconDiffs.filter((d) => d.batchNo === batch.batchNo);
  for (const d of diffs) d.resolved = false;
  return { batch, diffs };
}

// 这一组只读、不改状态，必须排在下面那些会推进状态机的用例之前：
// 种子不变量一旦被前面的用例改过，「明细与批次口径一致」就验不出来了。
describe("对账差错明细：与批次口径一致（种子不变量）", () => {
  it("每个有差异的批次，逐笔差额之和 = 批次差额（抽屉不会跟它上一层打架）", () => {
    for (const r of reconciles.filter((x) => x.status === "DIFF")) {
      const rows = listReconDiffs(r.batchNo);
      expect(rows.length, `批次 ${r.batchNo} 有差额必须有逐笔差错`).toBeGreaterThan(0);
      expect(sum(rows), `批次 ${r.batchNo} 逐笔加总`).toBe(Number(r.diff.toFixed(2)));
    }
  });

  it("已平批次一条差错行都没有（没有差错却列出差错行，比不列更糟）", () => {
    for (const r of reconciles.filter((x) => x.status === "MATCHED")) {
      expect(listReconDiffs(r.batchNo)).toHaveLength(0);
    }
  });

  it("种子里 resolved 与批次处置进度同源：终态全平、未结案一条未平", () => {
    for (const r of reconciles.filter((x) => x.status === "DIFF")) {
      const rows = reconDiffs.filter((d) => d.batchNo === r.batchNo);
      const terminal = r.handleStatus !== null && RECON_TERMINAL.includes(r.handleStatus);
      expect(rows.every((d) => d.resolved === terminal), `批次 ${r.batchNo}（${r.handleStatus}）`).toBe(true);
    }
  });

  it("不存在的批次要报错，不返回空数组糊过去", () => {
    expect(() => listReconDiffs("RC99999")).toThrow(/不存在/);
  });
});

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
    const done = handleRecon(r.batchNo, "channel", "已提 nearpay 差错工单 NP-2026-0501");

    expect(done.handleStatus).toBe("HANDLING");
    expect(done.handleResult).toBe("CHANNEL_ERROR");
    expect(done.handleNote).toBe("已提 nearpay 差错工单 NP-2026-0501");
    // 处置人按会话来，不是调用方说了算（mock 的会话就是 admin）。
    // 此前这里传 "Sara Ahmed" 并断言记成 "Sara Ahmed" —— 那正是被修掉的行为：
    // 后端原先是「有传参就用传参、否则才看会话」，而 handled_by 是对账要审的那一列。
    expect(done.handledBy).toBe("admin");
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

describe("对账差错明细：逐条处置（diffId）", () => {
  it("逐条处置只平掉指定那一条；批次进度要等全部平账才迁移（半平不算平）", () => {
    // 用有多条差错的批次才验得出「半平」
    const found = [0, 1, 2, 3].map(openReconWithDiffs).find((x) => x.diffs.length > 1)!;
    expect(found, "种子里应有差错行 >1 的批次").toBeTruthy();
    const { batch, diffs } = found;

    const done = handleRecon(batch.batchNo, "compensate", "先补这一笔 ADJ-0001", diffs[0].id);
    expect(diffs[0].resolved).toBe(true);
    expect(diffs.slice(1).every((d) => !d.resolved)).toBe(true);
    expect(done.handleStatus).toBe("OPEN"); // 还有没平的，进度不动
    expect(done.handleNote).toBe("先补这一笔 ADJ-0001"); // 但留痕要写（谁在什么时候平了一笔）
    // 处置人按会话来，不是调用方说了算（mock 的会话就是 admin）。
    // 此前这里传 "Sara Ahmed" 并断言记成 "Sara Ahmed" —— 那正是被修掉的行为：
    // 后端原先是「有传参就用传参、否则才看会话」，而 handled_by 是对账要审的那一列。
    expect(done.handledBy).toBe("admin");

    // 把剩下的逐条平掉，最后一条落地时批次才迁移
    for (const d of diffs.slice(1)) handleRecon(batch.batchNo, "compensate", "补齐剩余", d.id);
    expect(batch.handleStatus).toBe("RESOLVED");
    expect(batch.handleResult).toBe("COMPENSATED");
  });

  it("整批处置（不带 diffId）把该批次全部差错一次平掉", () => {
    const { batch, diffs } = openReconWithDiffs(1);
    handleRecon(batch.batchNo, "verify", "逐笔核对无误，整批结案");
    expect(diffs.every((d) => d.resolved)).toBe(true);
    expect(batch.handleStatus).toBe("IGNORED");
  });

  it("diffId 不属于该批次 / 已平账的差错，都拒绝处置", () => {
    const { batch, diffs } = openReconWithDiffs(2);
    const alien = reconDiffs.find((d) => d.batchNo !== batch.batchNo)!;
    expect(() => handleRecon(batch.batchNo, "verify", "越批处置", alien.id))
      .toThrow(/不属于对账批次/);
    expect(() => handleRecon(batch.batchNo, "verify", "不存在的明细", 999999))
      .toThrow(/不属于对账批次/);

    handleRecon(batch.batchNo, "verify", "核对无误", diffs[0].id);
    expect(() => handleRecon(batch.batchNo, "verify", "再来一次", diffs[0].id))
      .toThrow(/已平账，不可重复处置/);
  });

  it("逐条处置同样吃状态机与结论必填两道闸门（diffId 不是后门）", () => {
    const { batch, diffs } = openReconWithDiffs(3);
    expect(() => handleRecon(batch.batchNo, "verify", "  ", diffs[0].id)).toThrow(/结论必填/);
    expect(diffs[0].resolved).toBe(false); // 拒绝后不留半平状态

    handleRecon(batch.batchNo, "channel", "挂起等回执", diffs[0].id);
    // channel 的 from 只含 OPEN；批次仍是 OPEN（半平不迁移）时可以再定责，
    // 但一旦进了 HANDLING 就不许再定责一次——用整批处置把它推进 HANDLING 再验
    const { batch: b2 } = openReconWithDiffs(3);
    handleRecon(b2.batchNo, "channel", "整批挂起等回执");
    expect(b2.handleStatus).toBe("HANDLING");
    expect(() => handleRecon(b2.batchNo, "platform", "改判平台侧")).toThrow(/不允许执行/);
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
    const done = voidInvoice(issued.invoiceNo, "抬头填错，需重开");
    expect(done.status).toBe("VOID");
    expect(done.voidReason).toBe("抬头填错，需重开");
    // 作废人按会话来（mock 的会话就是 admin）：后端 InvoiceVoidReq 只认 voidReason，
    // voidedBy 由会话回填 —— 传什么就记什么等于审计链随手可伪造
    expect(done.voidedBy).toBe("admin");
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

// —— 账务分录：凭证下钻与借贷平衡（S9）——
// 账务分录页原先只有平铺列表。会计上有意义的单位是**凭证**（一借一贷必须等额），
// 逐条看判断不了平不平。这里把「每张凭证必须借贷平衡」钉成不变量 ——
// 不平的凭证是记账错误，是这一页最该拦住的东西。
describe("账务分录 · 凭证平衡", () => {
  it("每一张凭证都借贷平衡", () => {
    for (const no of new Set(ledger.map((e) => e.voucherNo))) {
      const b = voucherBalance(no);
      expect(b.balanced, `凭证 ${no} 不平：借 ${b.debit} / 贷 ${b.credit}`).toBe(true);
    }
  });

  it("凭证下钻取到的分录属于该凭证，且合计与 voucherBalance 一致", () => {
    const no = ledger[0].voucherNo;
    const es = listVoucherEntries(no);
    expect(es.length).toBeGreaterThan(0);
    for (const e of es) expect(e.voucherNo).toBe(no);
    const debit = es.filter((e) => e.direction === "DEBIT").reduce((n, e) => n + e.amount, 0);
    expect(voucherBalance(no).debit).toBeCloseTo(debit, 2);
  });

  it("不存在的凭证：空分录且判定为平衡（0 == 0），不抛错", () => {
    const b = voucherBalance("V-NOPE");
    expect(listVoucherEntries("V-NOPE")).toEqual([]);
    expect(b.balanced).toBe(true);
  });

  it("期间筛选真的收窄：近 7 日的分录数不多于近 12 月", () => {
    const n7 = listLedgerInPeriod({ period: "LAST_7D", size: 500 }).total;
    const n12 = listLedgerInPeriod({ period: "LAST_12M", size: 500 }).total;
    expect(n12).toBeGreaterThanOrEqual(n7);
  });
});

// —— 手工记账（S10）——
// 先前刻意不做，理由是「不校验平衡的手工记账比不做更危险」。做了，是因为把那条顾虑
// 变成了硬约束。这些用例钉的就是那几条约束 —— 它们一旦松掉，这个功能就变回危险品。
describe("账务分录 · 手工记账", () => {
  const ok = () => ({
    summary: "补记测试凭证",
    entries: [
      { account: "现金-nearpay", direction: "DEBIT" as const, amount: 30 },
      { account: "平台收入", direction: "CREDIT" as const, amount: 30 },
    ],
  });

  it("借贷相等才记账，且新凭证自身必须平衡", () => {
    const rows = createVoucher(ok());
    expect(rows.length).toBe(2);
    expect(voucherBalance(rows[0].voucherNo).balanced).toBe(true);
  });

  it("借贷不等直接拒（这是最该拦住的一条）", () => {
    const bad = ok();
    bad.entries[1].amount = 29.99;
    expect(() => createVoucher(bad)).toThrow(/借贷不平/);
  });

  it("单边凭证不是记账：只有借方或只有贷方一律拒", () => {
    expect(() => createVoucher({
      summary: "只有借方",
      entries: [
        { account: "现金", direction: "DEBIT", amount: 10 },
        { account: "银行", direction: "DEBIT", amount: 10 },
      ],
    })).toThrow(/都必须有分录/);
  });

  it("摘要必填 —— 手工凭证没有来源单据，摘要是唯一可审计线索", () => {
    expect(() => createVoucher({ ...ok(), summary: "  " })).toThrow(/摘要必填/);
  });

  it("金额必须为正：负数不是表达贷方的方式（方向由 direction 表达）", () => {
    const bad = ok();
    bad.entries[0].amount = -30;
    expect(() => createVoucher(bad)).toThrow(/大于 0/);
  });

  it("少于两条分录直接拒", () => {
    expect(() => createVoucher({ summary: "单条", entries: [{ account: "现金", direction: "DEBIT", amount: 5 }] }))
      .toThrow(/至少需要两条/);
  });

  it("凭证号由服务端派且不撞号；记账后总账里能查到该凭证", () => {
    const a = createVoucher(ok())[0].voucherNo;
    const b = createVoucher(ok())[0].voucherNo;
    expect(a).not.toBe(b);
    expect(listVoucherEntries(b).length).toBe(2);
  });

  it("记账只新增不改历史：既有凭证的分录数不受影响", () => {
    const existing = ledger.find((e) => e.voucherNo.startsWith("V2"))!.voucherNo;
    const before = listVoucherEntries(existing).length;
    createVoucher(ok());
    expect(listVoucherEntries(existing).length).toBe(before);
  });
});
