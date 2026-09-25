// 文件上传（平台域）。对应后端 `/api/platform/files` 与 `sys_file`。
//
// 2026-09-25 裁决：文件存腾讯云 COS，**统一经应用服务器上传**（不做前端直传）。
// 所以这里没有「取直传签名」那套：浏览器把文件 POST 给后端，后端转存并落元数据。

/**
 * 文件用途（SSOT）。与后端 `platform.file.FileCategory` 同名同值。
 *
 * <p>用途不是标签，它**决定了三件事**：谁能传（权限码）、能传什么（媒体类型与大小）、
 * 存哪个桶（PRIVATE / PII / PUBLIC）。所以上传时必须指定，不能有「其他」这种兜底档 ——
 * 兜底档一出现，所有人都会往那儿传，权限与桶的约束随即失效。
 */
export type FileCategory =
  | "CONTRACT_SCAN"
  | "WO_PHOTO"
  | "SURVEY_PHOTO"
  | "LEAD_PHOTO"
  | "AGENT_QUALIFICATION"
  | "CS_EVIDENCE"
  | "PUBLIC_IMAGE";

/**
 * 文件状态（SSOT）。与后端 `platform.file.FileStatus` 同名同值。
 *
 * <p>`TEMP` = 已上传但还没被任何业务单据引用。**它不是错误态** ——
 * 用户选了文件、还没点保存时就是这个状态；清理由定时任务按保留期做。
 */
export type FileStatus = "TEMP" | "BOUND" | "REMOVED" | "PURGED";

/** 上传成功后的文件元数据（后端 `FileRef`）。二进制本身不经过这里。 */
export interface FileRef {
  fileNo: string;
  category: FileCategory;
  status: FileStatus;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  /** 能不能在抽屉里直接预览（图片可以，PDF 走下载）。由后端按类型判定，前端不要自己猜。 */
  previewable: boolean;
  imageWidth: number | null;
  imageHeight: number | null;
  uploadedAt: string;
}

/** 限时下载地址。`expiresAt` 过了要重新取 —— 别把它存进组件 state 当长期链接用。 */
export interface SignedFileUrl {
  url: string;
  expiresAt: string;
}

/**
 * 各用途的上传约束（镜像后端 {@code FileCategory} 的构造参数）。
 *
 * <h3>为什么前端也要有一份</h3>
 * 不是为了校验 —— **后端才是权威，它会再查一遍**。这份的用途是
 * ① 在选文件之前就把「支持 PDF/JPG/PNG，最大 20MB」写在界面上；
 * ② 选了超大文件时立刻说清楚，而不是传了 20 秒再收一个 400。
 *
 * <p>⚠️ 它是**镜像**，会漂。跨端词表卡口只比 {@link FileCategory} 的取值，
 * 比不到这张表里的数字 —— 后端调了限制而这里没跟上，表现是
 * 「界面说能传 10MB，传了却被拒」。改后端那个枚举时记得回来看一眼。
 */
export const FILE_CATEGORY_RULES: Record<
  FileCategory,
  { label: string; accept: string; maxMb: number }
> = {
  CONTRACT_SCAN: { label: "合同扫描件", accept: ".pdf,.jpg,.jpeg,.png", maxMb: 20 },
  WO_PHOTO: { label: "工单照片", accept: ".jpg,.jpeg,.png", maxMb: 10 },
  SURVEY_PHOTO: { label: "勘场照片", accept: ".jpg,.jpeg,.png", maxMb: 10 },
  LEAD_PHOTO: { label: "商机照片", accept: ".jpg,.jpeg,.png", maxMb: 10 },
  AGENT_QUALIFICATION: { label: "代理资质", accept: ".pdf,.jpg,.jpeg,.png", maxMb: 10 },
  CS_EVIDENCE: { label: "客服凭证", accept: ".jpg,.jpeg,.png", maxMb: 5 },
  PUBLIC_IMAGE: { label: "公开图片", accept: ".jpg,.jpeg,.png,.webp", maxMb: 5 },
};

/** 人读的文件大小。列表里对齐用 tabular-nums，这里只管数字与单位。 */
export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
