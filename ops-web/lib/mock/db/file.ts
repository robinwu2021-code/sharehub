import type { FileCategory, FileRef, SignedFileUrl } from "../../types";
import { FILE_CATEGORY_RULES } from "../../types";
import { fail } from "../../biz-error";

/**
 * 文件 mock 库。
 *
 * <h3>为什么要真存一份而不是每次造个假 fileNo</h3>
 * 仓库约定：**mock 必须真改 db，重开能读回**。合同附件、工单照片都是
 * 「上传 → 拿 fileNo → 保存到单据上 → 再打开要能看见」，
 * 上传后不落库的话，离线开发时「保存」看着成功、刷新附件就没了。
 *
 * <h3>校验要和后端一样严</h3>
 * 与后端 `FileCategory` 同一套约束（用途、类型、大小）。
 * mock 放行而后端拒绝，离线调一路顺、切后端当场 400 ——
 * 这条坑本仓库踩过四次（见 mock/db/menu.ts 的同款说明）。
 *
 * <p>唯一不模拟的是**权限**：`FileCategory.uploadPerm` 由页面按钮的 `can()` 挡，
 * 不在 db 层重复一遍（重复的那一份迟早与 UI_PERM_MAP 不一致）。
 */

let seq = 9000;

/** 已上传的文件。key = fileNo。 */
const files = new Map<string, FileRef & { blobUrl: string }>();

/** 扩展名 → contentType。只认 FILE_CATEGORY_RULES 里出现过的那几种。 */
const CONTENT_TYPE: Record<string, string> = {
  pdf: "application/pdf",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
};

const extOf = (name: string): string => (name.split(".").pop() ?? "").toLowerCase();

/**
 * 上传。三道校验 + 落库，与后端同口径。
 *
 * <p>`progress` 由调用方（mocks/system.ts）模拟，这里只管落库 ——
 * 进度是传输层的事，不是存储层的。
 */
export function uploadFile(file: File, category: FileCategory): FileRef {
  const rule = FILE_CATEGORY_RULES[category];
  if (!rule) {
    fail(`文件用途非法：${category}`, `Invalid file category: ${category}`, `فئة ملف غير صالحة: ${category}`);
  }
  const ext = extOf(file.name);
  if (!rule.accept.split(",").includes(`.${ext}`)) {
    fail(
      `「${rule.label}」只支持 ${rule.accept}，这个文件是 .${ext}`,
      `${rule.label} only accepts ${rule.accept}`,
      `${rule.label} يقبل فقط ${rule.accept}`,
    );
  }
  if (file.size > rule.maxMb * 1024 * 1024) {
    fail(
      `「${rule.label}」最大 ${rule.maxMb}MB，这个文件 ${(file.size / 1024 / 1024).toFixed(1)}MB`,
      `${rule.label} max ${rule.maxMb}MB`,
      `${rule.label} الحد الأقصى ${rule.maxMb} ميجابايت`,
    );
  }

  const fileNo = `F${seq++}`;
  const contentType = CONTENT_TYPE[ext] ?? "application/octet-stream";
  const ref: FileRef & { blobUrl: string } = {
    fileNo,
    category,
    // TEMP = 还没被任何单据引用。**不是错误态** —— 选了文件还没点保存时就是它
    status: "TEMP",
    originalName: file.name,
    contentType,
    sizeBytes: file.size,
    previewable: contentType.startsWith("image/"),
    imageWidth: null,
    imageHeight: null,
    uploadedAt: new Date().toISOString(),
    // 离线也要能真的看见图：用 objectURL 而不是一个假地址
    blobUrl: typeof URL !== "undefined" && typeof URL.createObjectURL === "function"
      ? URL.createObjectURL(file)
      : "",
  };
  files.set(fileNo, ref);
  const { blobUrl: _ignored, ...pub } = ref;
  return pub;
}

/** 取限时地址。mock 里给 10 分钟，够看清「过期要重取」这件事。 */
export function fileUrl(fileNo: string): SignedFileUrl {
  const f = files.get(fileNo);
  if (!f) {
    fail(`文件不存在：${fileNo}`, `File not found: ${fileNo}`, `الملف غير موجود: ${fileNo}`);
  }
  return {
    url: f.blobUrl || `/mock-file/${fileNo}`,
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  };
}

/** 供别的 mock 切片引用（合同附件、工单照片要能读回文件名与大小）。 */
export function getFile(fileNo: string): FileRef | null {
  const f = files.get(fileNo);
  if (!f) return null;
  const { blobUrl: _ignored, ...pub } = f;
  return pub;
}

/** 业务单据引用了它 → TEMP 转 BOUND。保存单据时调。 */
export function bindFile(fileNo: string): void {
  const f = files.get(fileNo);
  if (f) f.status = "BOUND";
}
