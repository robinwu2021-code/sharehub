import { describe, it, expect } from "vitest";
import { uploadFile, fileUrl, getFile, bindFile } from "./file";
import { FILE_CATEGORY_RULES, type FileCategory } from "../../types";

/**
 * 文件上传的 mock：**校验要和后端一样严**。
 *
 * <p>mock 放行而后端拒绝，离线调一路顺、切后端当场 400 ——
 * 本仓库踩过四次（saveShareRule / saveVenueOnboarding / reviewVenueOnboarding / 两个详情端点）。
 * 所以这里逐条对着 `FileCategory` 的约束验：用途、类型、大小。
 */

/** 造一个指定大小与文件名的 File（不写真内容，size 由 Blob 决定）。 */
const fileOf = (name: string, bytes: number): File =>
  new File([new Uint8Array(bytes)], name, { type: "application/octet-stream" });

describe("文件上传 mock", () => {
  it("合同扫描件接受 PDF", () => {
    const ref = uploadFile(fileOf("合同.pdf", 1024), "CONTRACT_SCAN");
    expect(ref.fileNo).toMatch(/^F\d+$/);
    expect(ref.contentType).toBe("application/pdf");
    expect(ref.status).toBe("TEMP");
    expect(ref.previewable, "PDF 不能内联预览，走下载").toBe(false);
  });

  it("图片可预览——抽屉里直接显示，不必先下载", () => {
    expect(uploadFile(fileOf("现场.jpg", 2048), "WO_PHOTO").previewable).toBe(true);
  });

  it("★ 类型不在白名单 → 拒（工单照片不收 PDF）", () => {
    expect(() => uploadFile(fileOf("报告.pdf", 1024), "WO_PHOTO"))
      .toThrowError(/只支持|accepts/);
  });

  it("★ 超过大小上限 → 拒，且说清楚上限和实际多大", () => {
    const overCs = FILE_CATEGORY_RULES.CS_EVIDENCE.maxMb * 1024 * 1024 + 1;
    expect(() => uploadFile(fileOf("凭证.png", overCs), "CS_EVIDENCE"))
      .toThrowError(/最大 5MB|max 5MB/);
  });

  it("刚好等于上限 → 放行（边界不能反着来）", () => {
    const exact = FILE_CATEGORY_RULES.CS_EVIDENCE.maxMb * 1024 * 1024;
    expect(() => uploadFile(fileOf("凭证.png", exact), "CS_EVIDENCE")).not.toThrow();
  });

  it("★ 用途非法 → 拒（没有「其他」这种兜底档）", () => {
    expect(() => uploadFile(fileOf("x.png", 10), "WHATEVER" as FileCategory))
      .toThrowError(/用途非法|Invalid file category/);
  });

  it("★ 每一档的约束都真的生效——逐档验一遍不合法类型", () => {
    // 写死一档只能证明那一档；这里遍历，漏配某一档的规则会被抓到
    for (const [cat, rule] of Object.entries(FILE_CATEGORY_RULES)) {
      const badExt = rule.accept.includes(".pdf") ? ".txt" : ".pdf";
      expect(() => uploadFile(fileOf(`x${badExt}`, 10), cat as FileCategory), `${cat} 没有拦住 ${badExt}`)
        .toThrow();
    }
  });
});

describe("上传之后", () => {
  it("★ 真落库，读得回——上传完不落库的话，保存看着成功、刷新附件就没了", () => {
    const ref = uploadFile(fileOf("合同.pdf", 512), "CONTRACT_SCAN");
    expect(getFile(ref.fileNo)?.originalName).toBe("合同.pdf");
  });

  it("绑定后从 TEMP 转 BOUND", () => {
    const ref = uploadFile(fileOf("现场.png", 512), "SURVEY_PHOTO");
    expect(getFile(ref.fileNo)?.status).toBe("TEMP");
    bindFile(ref.fileNo);
    expect(getFile(ref.fileNo)?.status).toBe("BOUND");
  });

  it("取限时地址；不存在的文件要报错而不是给个死链", () => {
    const ref = uploadFile(fileOf("a.png", 128), "LEAD_PHOTO");
    expect(Date.parse(fileUrl(ref.fileNo).expiresAt)).toBeGreaterThan(Date.now());
    expect(() => fileUrl("F-nope")).toThrowError(/不存在|not found/i);
  });

  it("返回的元数据里不带内部字段（blobUrl 是 mock 自己的实现细节）", () => {
    const ref = uploadFile(fileOf("a.png", 128), "PUBLIC_IMAGE");
    expect(Object.keys(ref)).not.toContain("blobUrl");
  });
});
