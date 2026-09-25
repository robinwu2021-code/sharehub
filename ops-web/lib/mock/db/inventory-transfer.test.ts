import { describe, it, expect } from "vitest";
import { inventoryTransfers, saveInventoryTransfer, TransferError } from "./device";
import { TRANSFER_TRANSITIONS, canTransferAction, nextTransferStatuses } from "../../types";
import type { InvTransferStatus } from "../../types";

/**
 * 调拨单状态机（A3-3）的 mock 层回归。
 *
 * 钉的是**与后端 `InvTransferStateMachine` + `InventoryTransferServiceImpl` 的一致性**：
 * mock 放行而后端拒绝的话，页面在 mock 下看着是通的，切后端当场崩。
 * 这张表此前两样都没有 —— 前端没有迁移表、mock 是裸 upsert。
 */
const mk = (no: string, status: InvTransferStatus) => {
  const t = saveInventoryTransfer({ transferNo: no, fromLocation: "仓库A", toLocation: "站点B", powerbankCount: 10 });
  t.status = status;           // 直接摆到起始态（建单强制 DRAFT，见下面的用例）
  return t;
};

describe("建单一律 DRAFT", () => {
  it("传 IN_TRANSIT 也会被压成 DRAFT——不接受调用方直接开在途单", () => {
    const t = saveInventoryTransfer({ fromLocation: "仓库A", toLocation: "站点B", status: "IN_TRANSIT" });
    expect(t.status).toBe("DRAFT");
  });

  it("传 DONE 同样被压成 DRAFT——否则可以凭空造一张已完成的调拨单", () => {
    const t = saveInventoryTransfer({ fromLocation: "仓库A", toLocation: "站点C", status: "DONE" });
    expect(t.status).toBe("DRAFT");
  });
});

describe("合法迁移放行，非法迁移拒", () => {
  it("DRAFT --ship--> IN_TRANSIT", () => {
    const t = mk("TR-SM-1", "DRAFT");
    expect(saveInventoryTransfer({ transferNo: t.transferNo, status: "IN_TRANSIT" }).status).toBe("IN_TRANSIT");
  });

  it("IN_TRANSIT --receive--> DONE", () => {
    const t = mk("TR-SM-2", "IN_TRANSIT");
    expect(saveInventoryTransfer({ transferNo: t.transferNo, status: "DONE" }).status).toBe("DONE");
  });

  it("DRAFT 不能直接跳 DONE——跳过在途等于货没发就记收到了", () => {
    const t = mk("TR-SM-3", "DRAFT");
    expect(() => saveInventoryTransfer({ transferNo: t.transferNo, status: "DONE" })).toThrow(TransferError);
    expect(t.status).toBe("DRAFT");
  });

  it("DONE 是终态，不能退回在途——要退货应开一张反向调拨单，留两条痕", () => {
    const t = mk("TR-SM-4", "DONE");
    expect(() => saveInventoryTransfer({ transferNo: t.transferNo, status: "IN_TRANSIT" })).toThrow(TransferError);
    expect(t.status).toBe("DONE");
  });

  it("不传 status 或传当前状态：只改单头字段，不当成迁移", () => {
    const t = mk("TR-SM-5", "IN_TRANSIT");
    expect(saveInventoryTransfer({ transferNo: t.transferNo, powerbankCount: 20 }).status).toBe("IN_TRANSIT");
    expect(saveInventoryTransfer({ transferNo: t.transferNo, status: "IN_TRANSIT" }).powerbankCount).toBe(20);
  });
});

describe("下拉选项 = 保持当前 + 合法的下一步", () => {
  it("每个状态给出的选项都能被状态机收下——界面给得出的，后端就该收得下", () => {
    for (const from of ["DRAFT", "IN_TRANSIT", "DONE"] as const) {
      for (const to of nextTransferStatuses(from)) {
        if (to === from) continue;          // 「保持当前」不是迁移
        const action = (Object.keys(TRANSFER_TRANSITIONS) as ("ship" | "receive")[])
          .find((a) => TRANSFER_TRANSITIONS[a].to === to)!;
        expect(canTransferAction(from, action)).toBe(true);
      }
    }
  });

  it("DONE 是终态：只剩「保持当前」一个选项", () => {
    expect(nextTransferStatuses("DONE")).toEqual(["DONE"]);
  });

  it("DRAFT 给草稿与在途两项，不给已完成", () => {
    expect(nextTransferStatuses("DRAFT")).toEqual(["DRAFT", "IN_TRANSIT"]);
  });
});

describe("与后端 InvTransferStateMachine 的边逐条一致", () => {
  it("两条边，一条都不多", () => {
    expect(Object.keys(TRANSFER_TRANSITIONS).sort()).toEqual(["receive", "ship"]);
    expect(TRANSFER_TRANSITIONS.ship).toMatchObject({ from: ["DRAFT"], to: "IN_TRANSIT" });
    expect(TRANSFER_TRANSITIONS.receive).toMatchObject({ from: ["IN_TRANSIT"], to: "DONE" });
  });

  it("种子里的单子状态都在词表内", () => {
    for (const t of inventoryTransfers) {
      expect(["DRAFT", "IN_TRANSIT", "DONE"]).toContain(t.status);
    }
  });
});
