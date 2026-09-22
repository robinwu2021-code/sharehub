// 进场合同附件（扫描件）与站点坐标两道写入闸门。
//
// 附件是**假上传**（拍板点 #3）：只登记文件名 + 大小，字节流不传。正因为内容不可校验，
// 名称/格式/大小/同名这几道判断就是唯一的把关点，必须在 db 层而不是页面上。
// 站点坐标合并在此文件：两者都属「本次补的写入入口」，且都靠 db 层守卫兜住脏数据。
import { describe, expect, it } from "vitest";
import {
  contracts, addContractAttachment, removeContractAttachment, ContractAttachmentError,
  saveContract, assertSiteCoords, SiteCoordError,
} from "./location";
import { ATTACH_EXTS, ATTACH_MAX_SIZE, SITE_COORD_BOUNDS } from "../../types";

const ctOf = (no: string) => contracts.find((c) => c.contractNo === no)!;
const withAttachment = () => contracts.find((c) => c.attachments.length > 0)!;

describe("种子自洽：附件流水不与合同打架", () => {
  it("每份附件都有编号/大小/上传人，且上传时间不早于合同生效时间", () => {
    for (const c of contracts) {
      for (const a of c.attachments) {
        expect(a.attachNo).toMatch(/^ATT\d+$/);
        expect(a.size).toBeGreaterThan(0);
        expect(a.uploadedBy).toBeTruthy();
        expect(a.uploadedAt >= c.startAt, `${c.contractNo}/${a.attachNo} 扫描件早于合同生效`).toBe(true);
      }
    }
  });

  it("附件编号全局唯一（跨合同）", () => {
    const all = contracts.flatMap((c) => c.attachments.map((a) => a.attachNo));
    expect(all.length).toBe(new Set(all).size);
  });

  it("既有已上传的合同也有没上传的合同（两种态都能在页面上看到）", () => {
    expect(contracts.some((c) => c.attachments.length > 0)).toBe(true);
    expect(contracts.some((c) => c.attachments.length === 0)).toBe(true);
  });
});

describe("附件登记守卫", () => {
  const target = "CT401";

  it("合同不存在直接拒绝", () => {
    expect(() => addContractAttachment("CT9999", { fileName: "a.pdf", size: 100 })).toThrow(ContractAttachmentError);
  });

  it("格式白名单：不在 ATTACH_EXTS 内一律拒绝（含无扩展名）", () => {
    for (const bad of ["合同.docx", "合同.zip", "合同"]) {
      expect(() => addContractAttachment(target, { fileName: bad, size: 1000 })).toThrow(/不支持的文件格式/);
    }
    // 白名单里的格式必须都能过
    for (const ext of ATTACH_EXTS) {
      expect(addContractAttachment(target, { fileName: `ok-${ext}.${ext}`, size: 2048 }).attachments[0].fileName).toBe(`ok-${ext}.${ext}`);
    }
  });

  it("大小：空文件与超限文件都拒绝", () => {
    expect(() => addContractAttachment(target, { fileName: "empty.pdf", size: 0 })).toThrow(/文件大小非法/);
    expect(() => addContractAttachment(target, { fileName: "huge.pdf", size: ATTACH_MAX_SIZE + 1 })).toThrow(/上限/);
    expect(() => addContractAttachment(target, { fileName: "edge.pdf", size: ATTACH_MAX_SIZE })).not.toThrow();
  });

  it("同名拒绝：没有内容哈希可比，允许同名就分不出哪份是最新的", () => {
    addContractAttachment(target, { fileName: "dup.pdf", size: 1000 });
    expect(() => addContractAttachment(target, { fileName: "dup.pdf", size: 2000 })).toThrow(/同名附件已存在/);
    // 换一份合同同名是合法的（同名约束按合同收敛）
    expect(() => addContractAttachment("CT402", { fileName: "dup.pdf", size: 1000 })).not.toThrow();
  });

  it("文件名必填，前后空白不算内容", () => {
    expect(() => addContractAttachment(target, { fileName: "   ", size: 1000 })).toThrow(/文件名必填/);
  });
});

describe("附件登记与移除", () => {
  it("登记：返回整份合同，新件置顶，上传人/时间落库", () => {
    const c = addContractAttachment("CT403", { fileName: "扫描件-新.pdf", size: 555_000, uploadedBy: "Sara Ops" });
    expect(c.contractNo).toBe("CT403");
    expect(c.attachments[0]).toMatchObject({ fileName: "扫描件-新.pdf", size: 555_000, uploadedBy: "Sara Ops" });
    expect(c.attachments[0].uploadedAt).toBeTruthy();
    // 不传上传人时退化为 admin（与流转留痕同口径）
    expect(addContractAttachment("CT403", { fileName: "扫描件-2.pdf", size: 1000 }).attachments[0].uploadedBy).toBe("admin");
  });

  it("移除：只掉指定那份，移除不存在的报错", () => {
    const c = addContractAttachment("CT404", { fileName: "待撤回.pdf", size: 1000 });
    const no = c.attachments[0].attachNo;
    const kept = c.attachments.length - 1;
    expect(removeContractAttachment("CT404", no).attachments.length).toBe(kept);
    expect(() => removeContractAttachment("CT404", no)).toThrow(/附件不存在/);
  });

  it("编辑合同不会顺手清空附件（编辑抽屉的提交体里没有 attachments）", () => {
    const c = withAttachment();
    const before = c.attachments.length;
    expect(before).toBeGreaterThan(0);
    saveContract({ contractNo: c.contractNo, entryFee: 1234 });
    expect(ctOf(c.contractNo).entryFee).toBe(1234);
    expect(ctOf(c.contractNo).attachments.length).toBe(before);
  });

  it("新建合同的附件位是空数组而不是 undefined（列表要读 .length）", () => {
    const created = saveContract({ venueName: "New Venue", siteName: "New Site", shareRate: 0.2, entryFee: 0, status: "ACTIVE", startAt: "2026-08-01", endAt: "2027-08-01" });
    expect(created.attachments).toEqual([]);
  });
});

describe("站点坐标闸门", () => {
  const { latMin, latMax, lngMin, lngMax } = SITE_COORD_BOUNDS;

  it("缺失或非数一律拒绝（留空的站点在地图上是「凭空消失」而不是报错）", () => {
    for (const bad of [[undefined, undefined], [25.2, undefined], [null, 55.2], ["abc", "55.2"], [NaN, 55.2]]) {
      expect(() => assertSiteCoords(bad[0], bad[1]), JSON.stringify(bad)).toThrow(SiteCoordError);
    }
  });

  it("经纬度写反能被抓住 —— 这是全球范围内合法、却把点扔到海里的典型脏数据", () => {
    expect(() => assertSiteCoords(55.2708, 25.2048)).toThrow(/超出运营范围/);
  });

  it("窗口内放行（含边界），窗口外拒绝", () => {
    expect(() => assertSiteCoords(25.2048, 55.2708)).not.toThrow(); // 迪拜市中心
    expect(() => assertSiteCoords(latMin, lngMin)).not.toThrow();
    expect(() => assertSiteCoords(latMax, lngMax)).not.toThrow();
    expect(() => assertSiteCoords(latMax + 0.1, lngMin)).toThrow(SiteCoordError);
    expect(() => assertSiteCoords(latMin, lngMax + 0.1)).toThrow(SiteCoordError);
  });

  it("数字字符串放行：number input 在清空重填时会给字符串", () => {
    expect(() => assertSiteCoords("25.2048", "55.2708")).not.toThrow();
    expect(() => assertSiteCoords("", "55.2708")).toThrow(SiteCoordError);
  });
});
