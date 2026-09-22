// 钱包流水与钱包余额的自洽单测。
//
// 背景：后端 GET /api/user/wallets/{userNo}/txns 早就实现，运营端一直没有入口；
// 补入口时最容易翻车的地方不是接口，而是**假数据自相矛盾** ——
// 流水抽屉是从余额列表点开的，如果一条条加起来不等于那一行的余额，页面就在自证数据是假的。
//
// 本文件钉住三件事：
//   ① 本金流水（RECHARGE/SPEND/REFUND）带符号求和 === wallets[].balance
//   ② 赠额流水（BONUS）求和 === wallets[].bonus
//   ③ 手工调余额（saveWallet）会补一条调整流水，调完① ②依然成立
import { describe, it, expect } from "vitest";
import { wallets, walletTxns, listWalletTxns, saveWallet, sumPrincipal, sumBonus } from "./user";

describe("钱包流水与余额自洽", () => {
  it("每个钱包的本金流水合计 === 余额，赠额流水合计 === 赠额", () => {
    for (const w of wallets) {
      const rows = walletTxns[w.userNo] ?? [];
      expect(sumPrincipal(rows), `${w.userNo} 本金`).toBe(w.balance);
      expect(sumBonus(rows), `${w.userNo} 赠额`).toBe(w.bonus);
    }
  });

  it("金额符号与 direction 一致（IN 正 / OUT 负）", () => {
    for (const rows of Object.values(walletTxns)) {
      for (const r of rows) expect(r.direction, r.txnNo).toBe(r.amount >= 0 ? "IN" : "OUT");
    }
  });

  it("最新在前：数组顺序与 createdAt 同向（后端按主键倒序，两边必须一致）", () => {
    for (const rows of Object.values(walletTxns)) {
      for (let i = 1; i < rows.length; i++) {
        expect(rows[i].createdAt <= rows[i - 1].createdAt, `${rows[i].txnNo}`).toBe(true);
      }
    }
  });

  it("分页与类型筛选：total 是筛选后的条数，翻页不重不漏", () => {
    const w = wallets.find((x) => (walletTxns[x.userNo] ?? []).length > 3)!;
    const all = listWalletTxns(w.userNo, { size: 500 });
    expect(all.total).toBe(walletTxns[w.userNo].length);
    const p1 = listWalletTxns(w.userNo, { page: 1, size: 2 });
    const p2 = listWalletTxns(w.userNo, { page: 2, size: 2 });
    expect(p1.list.concat(p2.list).map((x) => x.txnNo)).toEqual(all.list.slice(0, 4).map((x) => x.txnNo));

    const spend = listWalletTxns(w.userNo, { type: "SPEND", size: 500 });
    expect(spend.total).toBe(all.list.filter((x) => x.type === "SPEND").length);
    expect(spend.list.every((x) => x.type === "SPEND")).toBe(true);
  });

  it("手工调余额会补一条调整流水，合计仍然对得上", () => {
    const w = wallets[3];
    const before = (walletTxns[w.userNo] ?? []).length;
    const after = saveWallet({ userNo: w.userNo, balance: w.balance + 25, bonus: w.bonus + 5 });
    const rows = walletTxns[w.userNo];
    expect(rows.length).toBe(before + 2); // 余额一条 + 赠额一条
    expect(sumPrincipal(rows)).toBe(after.balance);
    expect(sumBonus(rows)).toBe(after.bonus);
  });

  it("不存在的用户返回空页而不是抛错（钱包页可能被 deep link 带着陌生用户号进来）", () => {
    expect(listWalletTxns("U-not-exist")).toMatchObject({ list: [], total: 0 });
  });
});
