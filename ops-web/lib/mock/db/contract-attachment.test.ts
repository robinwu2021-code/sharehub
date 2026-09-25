// 进场合同附件（扫描件）与站点坐标两道写入闸门。
//
// 附件走文件服务（2026-09-25）：先 uploadFile 拿 fileNo，再按 fileNo 挂到合同上。
// 类型 / 大小由文件服务按用途把关；挂附件这一步管的是「文件存在、用途对、合同还没结束、不重复挂」。
// 站点坐标合并在此文件：两者都属「本次补的写入入口」，且都靠 db 层守卫兜住脏数据。
import { describe, expect, it } from "vitest";
import {
  contracts, addContractAttachment, removeContractAttachment, ContractAttachmentError,
  saveContract, assertSiteCoords, SiteCoordError,
} from "./location";
import { ATTACH_EXTS, ATTACH_MAX_SIZE, SITE_COORD_BOUNDS, type FileCategory } from "../../types";
import { uploadFile, getFile } from "./file";

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

describe("附件登记守卫（先经文件服务上传，再按 fileNo 挂）", () => {
  const target = "CT401";
  const up = (name: string, size = 2048, category: FileCategory = "CONTRACT_SCAN") =>
    uploadFile(new File([new Uint8Array(size)], name), category).fileNo;

  it("合同不存在直接拒绝", () => {
    expect(() => addContractAttachment("CT9999", { fileNos: [up("a.pdf")] })).toThrow(ContractAttachmentError);
  });

  it("fileNos 必填：不先上传就挂不上", () => {
    expect(() => addContractAttachment(target, { fileNos: [] })).toThrow(/fileNos/);
  });

  it("文件必须存在且用途是合同扫描件 —— 工单照片挂到合同上会让对账凭据张冠李戴", () => {
    expect(() => addContractAttachment(target, { fileNos: ["F-NOPE"] })).toThrow(/文件不存在/);
    expect(() => addContractAttachment(target, { fileNos: [up("site.jpg", 100, "WO_PHOTO")] })).toThrow(/不是合同扫描件/);
  });

  it("白名单格式都能挂上；名字与大小取文件服务的记录，不信前端声明", () => {
    for (const ext of ATTACH_EXTS) {
      const fileNo = up(`ok-${ext}.${ext}`, 3000);
      const a = addContractAttachment(target, { fileNos: [fileNo] }).attachments[0];
      expect(a).toMatchObject({ fileName: `ok-${ext}.${ext}`, size: 3000, fileNo });
    }
    expect(ATTACH_MAX_SIZE).toBeGreaterThan(0);
  });

  it("同一文件重复挂只算一次（幂等）", () => {
    const fileNo = up("dup.pdf");
    const n = addContractAttachment(target, { fileNos: [fileNo] }).attachments.length;
    expect(addContractAttachment(target, { fileNos: [fileNo, fileNo] }).attachments.length).toBe(n);
  });

  it("★ 已到期 / 已终止的合同不再收附件 —— 结束后补进来的「签署件」说明不了签署时的状态", () => {
    const ended = contracts.find((c) => c.status === "EXPIRED")!;
    expect(() => addContractAttachment(ended.contractNo, { fileNos: [up("late.pdf")] })).toThrow(/已结束/);
  });
});

describe("附件登记与移除", () => {
  it("登记：返回整份合同，新件置顶，上传人/时间落库，文件转 BOUND", () => {
    const f = uploadFile(new File([new Uint8Array(555)], "扫描件-新.pdf"), "CONTRACT_SCAN");
    expect(getFile(f.fileNo)?.status).toBe("TEMP");
    const c = addContractAttachment("CT403", { fileNos: [f.fileNo] });
    expect(c.contractNo).toBe("CT403");
    expect(c.attachments[0]).toMatchObject({ fileName: "扫描件-新.pdf", size: 555, uploadedBy: "admin" });
    expect(c.attachments[0].uploadedAt).toBeTruthy();
    expect(getFile(f.fileNo)?.status, "挂上即被引用，清理任务不能再动它").toBe("BOUND");
  });

  it("移除：只掉指定那份，移除不存在的报错", () => {
    const f = uploadFile(new File([new Uint8Array(10)], "待撤回.pdf"), "CONTRACT_SCAN");
    const c = addContractAttachment("CT404", { fileNos: [f.fileNo] });
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
