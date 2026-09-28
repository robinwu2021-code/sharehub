"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { ImageOff, Loader2 } from "lucide-react";

/**
 * 报障截图：用户在 C 端交上来的现场照片。
 *
 * <p><b>为什么不用 `useFileUrl`</b>：那条路取的是限时签名地址，而截图落在 PII 桶，
 * 后端对个人数据一律拒发签名地址（链接一旦被转发或进了日志就等于泄露）。
 * 这里带 token 取字节再包成 blob 地址，地址只在本页生命周期内有效。
 */
export function TicketPhotos({ fileNos }: { fileNos: string[] }) {
  return (
    <div className="grid grid-cols-3 gap-3">
      {fileNos.map((no) => (
        <Photo key={no} fileNo={no} />
      ))}
    </div>
  );
}

function Photo({ fileNo }: { fileNo: string }) {
  const [url, setUrl] = useState<string>();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    let created: string | undefined;
    setUrl(undefined);
    setFailed(false);
    api.fileBlobUrl(fileNo)
      .then((u) => {
        created = u;
        // 组件已卸载就立刻回收，别把地址留在内存里
        if (live) setUrl(u);
        else URL.revokeObjectURL(u);
      })
      .catch(() => { if (live) setFailed(true); });
    return () => {
      live = false;
      if (created) URL.revokeObjectURL(created);
    };
  }, [fileNo]);

  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer"
      className="flex aspect-square items-center justify-center overflow-hidden rounded-control bg-secondary"
      // 还没取到就别让点击跳空白页
      onClick={(e) => { if (!url) e.preventDefault(); }}
    >
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element -- blob 地址，不走 next/image 的优化管线
        <img src={url} alt="报障截图" className="size-full object-cover" />
      ) : failed ? (
        // 失败要显形：这是用户提交的证据，静默不出图会被当成「他没传」
        <span className="flex flex-col items-center gap-1 text-muted-foreground">
          <ImageOff className="size-5" />
          <span className="txt-caption">打不开</span>
        </span>
      ) : (
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      )}
    </a>
  );
}
