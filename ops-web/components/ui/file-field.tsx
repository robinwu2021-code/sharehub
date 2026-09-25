"use client";

// 文件字段（方案 C7）：多文件、上传进度、失败重试、图片缩略图；按用途（category）提示类型与大小。
//
// 值是 **fileNo 数组** —— 只有上传成功的文件进值；上传中 / 失败的只活在组件里。
// 否则「还在传」的文件会随表单一起提交，后端拿到一个不存在的 fileNo。
//
// 为什么选文件时就传、而不是点保存时一起传：
// 现场照片动辄几 MB，保存时才开始传，用户会盯着一个转圈的保存按钮十几秒，
// 其间任何一张失败都会让整张表单失败，而他不知道是哪一张。
//
// 存储定为 COS、统一经应用服务器上传（2026-09-25 裁决），所以这里只调 `api.uploadFile`，
// 没有「取直传签名」那一套；缩略图地址每次现取（`api.fileUrl`），它是限时的，不缓存成长期链接。
import * as React from "react";
import { FileText, ImagePlus, Loader2, RotateCcw, X } from "lucide-react";
import { api } from "@/lib/api";
import { FILE_CATEGORY_RULES, fileSize, type FileCategory, type FileRef } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Button } from "./button";

type ItemStatus = "uploading" | "done" | "failed";
interface Item {
  key: string;
  status: ItemStatus;
  name: string;
  size: number;
  /** 0..1 */
  progress: number;
  fileNo?: string;
  error?: string;
  /** 本地选的文件（重试要用；也用来出本地预览，免得为刚传的图再取一次签名地址）。 */
  file?: File;
  localUrl?: string;
  previewable?: boolean;
}

let seq = 0;
const nextKey = () => `f${Date.now()}-${seq++}`;
const extOf = (name: string) => `.${(name.split(".").pop() ?? "").toLowerCase()}`;
const isImageName = (name: string) => /\.(jpe?g|png|webp|gif)$/i.test(name);

/** 选文件时先按用途规则挡一遍（后端会再查一遍，这里只为了「选了就知道不行」）。 */
function precheck(file: File, category: FileCategory): string | null {
  const rule = FILE_CATEGORY_RULES[category];
  if (!rule.accept.split(",").includes(extOf(file.name))) return `只支持 ${rule.accept}`;
  if (file.size > rule.maxMb * 1024 * 1024) return `超过 ${rule.maxMb}MB（${fileSize(file.size)}）`;
  return null;
}

export interface FileFieldProps {
  /** 已上传成功的 fileNo。 */
  value: string[];
  onChange: (fileNos: string[]) => void;
  /** 用途：决定能传什么类型、多大（见 FILE_CATEGORY_RULES）。 */
  category: FileCategory;
  /** 最多几个，默认 9。 */
  max?: number;
  disabled?: boolean;
  invalid?: boolean;
  onBlur?: () => void;
  /** 注入上传实现（测试 / 组件总览页用）；默认 `api.uploadFile`。 */
  upload?: (file: File, category: FileCategory, onProgress?: (p: number) => void) => Promise<FileRef>;
  className?: string;
}

export function FileField({
  value, onChange, category, max = 9, disabled, invalid, onBlur, upload = api.uploadFile, className,
}: FileFieldProps) {
  const rule = FILE_CATEGORY_RULES[category];
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [items, setItems] = React.useState<Item[]>(() =>
    value.map((no) => ({ key: nextKey(), status: "done" as const, name: no, size: 0, progress: 1, fileNo: no })));
  const [dragOver, setDragOver] = React.useState(false);

  // 最新值走 ref：上传是异步的，完成回调里拿闭包里的 value 会丢掉同时在传的另一张
  const valueRef = React.useRef(value);
  valueRef.current = value;

  // 外部改了值（表单重置 / 换了一条记录）：已完成的项跟着值走，上传中 / 失败的保留
  React.useEffect(() => {
    setItems((cur) => {
      const kept = cur.filter((it) => it.status !== "done" || (it.fileNo && value.includes(it.fileNo)));
      const have = new Set(kept.map((it) => it.fileNo).filter(Boolean));
      const added = value.filter((no) => !have.has(no))
        .map((no) => ({ key: nextKey(), status: "done" as const, name: no, size: 0, progress: 1, fileNo: no }));
      return added.length || kept.length !== cur.length ? [...kept, ...added] : cur;
    });
  }, [value]);

  // 卸载时回收本地预览地址
  const itemsRef = React.useRef(items);
  itemsRef.current = items;
  React.useEffect(() => () => { itemsRef.current.forEach((it) => it.localUrl && URL.revokeObjectURL(it.localUrl)); }, []);

  const patch = (key: string, p: Partial<Item>) => setItems((cur) => cur.map((it) => (it.key === key ? { ...it, ...p } : it)));

  const run = async (key: string, file: File) => {
    patch(key, { status: "uploading", progress: 0, error: undefined });
    try {
      const ref = await upload(file, category, (p) => patch(key, { progress: p }));
      patch(key, { status: "done", progress: 1, fileNo: ref.fileNo, previewable: ref.previewable });
      onChange([...valueRef.current, ref.fileNo]);
    } catch (e) {
      patch(key, { status: "failed", error: e instanceof Error ? e.message : "上传失败" });
    }
  };

  const addFiles = (files: FileList | File[]) => {
    const room = max - items.filter((it) => it.status !== "failed").length;
    const list = Array.from(files);
    const accepted = list.slice(0, Math.max(0, room));
    const fresh: Item[] = accepted.map((file) => {
      const bad = precheck(file, category);
      return {
        key: nextKey(), status: bad ? "failed" : "uploading", name: file.name, size: file.size, progress: 0,
        error: bad ?? undefined, file: bad ? undefined : file,
        localUrl: !bad && isImageName(file.name) && typeof URL.createObjectURL === "function" ? URL.createObjectURL(file) : undefined,
        previewable: isImageName(file.name),
      };
    });
    if (list.length > accepted.length) {
      fresh.push({
        key: nextKey(), status: "failed", name: `另有 ${list.length - accepted.length} 个文件未加入`, size: 0, progress: 0,
        error: `最多 ${max} 个`,
      });
    }
    setItems((cur) => [...cur, ...fresh]);
    fresh.forEach((it) => { if (it.status === "uploading" && it.file) void run(it.key, it.file); });
    onBlur?.();
  };

  const remove = (it: Item) => {
    if (it.localUrl) URL.revokeObjectURL(it.localUrl);
    setItems((cur) => cur.filter((x) => x.key !== it.key));
    if (it.fileNo) onChange(valueRef.current.filter((no) => no !== it.fileNo));
    onBlur?.();
  };

  const full = items.filter((it) => it.status !== "failed").length >= max;

  return (
    <div className={cn("space-y-2", className)}>
      {items.length > 0 && (
        <ul className="grid grid-cols-3 gap-2 sm:grid-cols-4">
          {items.map((it) => (
            <li key={it.key} className="relative">
              <FileTile item={it} />
              <div className="mt-1 truncate txt-caption text-muted-foreground" title={it.name}>{it.name}</div>
              {it.status === "uploading" && (
                <div role="progressbar" aria-valuenow={Math.round(it.progress * 100)} aria-valuemin={0} aria-valuemax={100}
                  className="mt-1 h-1 w-full overflow-hidden rounded-chip bg-secondary">
                  <div className="h-full rounded-chip bg-primary transition-[width]" style={{ width: `${Math.round(it.progress * 100)}%` }} />
                </div>
              )}
              {it.status === "failed" && (
                <div className="mt-1 space-y-1">
                  <div className="txt-caption text-destructive-ink">{it.error}</div>
                  {it.file && (
                    <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={() => void run(it.key, it.file!)}>
                      <RotateCcw className="size-3.5" />重试
                    </Button>
                  )}
                </div>
              )}
              {!disabled && it.status !== "uploading" && (
                <button
                  type="button"
                  aria-label={`移除 ${it.name}`}
                  onClick={() => remove(it)}
                  className="absolute end-1 top-1 rounded-chip bg-card p-0.5 text-muted-foreground shadow-[var(--card-shadow)] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <X className="size-3.5" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {!full && (
        <div
          onDragOver={(e) => { if (disabled) return; e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => { if (disabled) return; e.preventDefault(); setDragOver(false); addFiles(e.dataTransfer.files); }}
          className={cn(
            "flex items-center gap-3 rounded-field border border-dashed border-border bg-secondary/50 px-3.5 py-3",
            dragOver && "border-primary bg-accent-subtle",
            invalid && "ring-2 ring-destructive",
            disabled && "opacity-50",
          )}
        >
          <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={() => inputRef.current?.click()}>
            <ImagePlus className="size-4" />选择文件
          </Button>
          <span className="txt-caption text-muted-foreground">
            {rule.label}：支持 {rule.accept}，单个最大 {rule.maxMb}MB，最多 {max} 个；可拖入
          </span>
          <input
            ref={inputRef}
            type="file"
            multiple={max > 1}
            accept={rule.accept}
            className="hidden"
            onChange={(e) => { if (e.target.files?.length) addFiles(e.target.files); e.target.value = ""; }}
          />
        </div>
      )}
    </div>
  );
}

/** 一格缩略图：本地预览优先；已有的 fileNo 现取限时地址。非图片显示文件图标。 */
function FileTile({ item }: { item: Item }) {
  const url = useFileUrl(item.localUrl ? undefined : item.status === "done" && item.previewable !== false ? item.fileNo : undefined);
  const src = item.localUrl ?? url;
  return (
    <div className="flex aspect-square w-full items-center justify-center overflow-hidden rounded-control bg-secondary">
      {item.status === "uploading" && !src ? (
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      ) : src ? (
        // eslint-disable-next-line @next/next/no-img-element -- 限时签名地址，不走 next/image 的优化管线
        <img src={src} alt={item.name} className={cn("size-full object-cover", item.status === "uploading" && "opacity-60")} />
      ) : (
        <FileText className="size-6 text-muted-foreground" />
      )}
    </div>
  );
}

/** 取限时地址。失败（文件已清理 / 无权限）就不出图，退回文件图标 —— 缩略图不值得弹错。 */
export function useFileUrl(fileNo: string | undefined): string | undefined {
  const [url, setUrl] = React.useState<string>();
  React.useEffect(() => {
    let live = true;
    setUrl(undefined);
    if (!fileNo) return;
    api.fileUrl(fileNo).then((r) => { if (live) setUrl(r.url); }).catch(() => { /* 退回图标 */ });
    return () => { live = false; };
  }, [fileNo]);
  return url;
}
