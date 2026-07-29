"use client";

// 统一确认弹窗（删除/高危操作）。刻意做成 hook 而非 Provider：
// 静态导出 SPA，页面各自渲染 {dialog} 即可，不牵动 app/providers.tsx。
//
//   const { confirm, dialog } = useConfirm();
//   const ok = await confirm({ title: "删除告警代码", danger: true, requireText: "OFFLINE" });
//   ... 页面里渲染 {dialog}
import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Button } from "./button";
import { Input } from "./input";
import { useI18n } from "@/lib/i18n";

export interface ConfirmOptions {
  title: string;
  desc?: string;
  /** 危险操作：确认按钮用 destructive 配色 */
  danger?: boolean;
  confirmText?: string;
  cancelText?: string;
  /** 高危项：要求输入完全一致的文本才启用确认 */
  requireText?: string;
}

interface Pending extends ConfirmOptions {
  resolve: (ok: boolean) => void;
}

export function useConfirm() {
  const { t } = useI18n();
  const [pending, setPending] = React.useState<Pending | null>(null);
  const [typed, setTyped] = React.useState("");

  // resolve 存 ref：state updater 必须是纯函数（StrictMode 下会执行两次），
  // 在其中调 resolve 属副作用，故把回调移出 updater。
  const resolveRef = React.useRef<((ok: boolean) => void) | null>(null);

  const confirm = React.useCallback((opts: ConfirmOptions) => {
    setTyped("");
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve;
      setPending({ ...opts, resolve });
    });
  }, []);

  const close = React.useCallback((ok: boolean) => {
    const resolve = resolveRef.current;
    resolveRef.current = null;
    setPending(null);
    setTyped("");
    resolve?.(ok);
  }, []);

  // requireText 存在时必须完全一致（不 trim、不忽略大小写）
  const locked = !!pending?.requireText && typed !== pending.requireText;

  const dialog = (
    <Dialog.Root
      open={!!pending}
      // ESC / 点遮罩 关闭 = 取消
      onOpenChange={(o) => { if (!o) close(false); }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/40 data-[state=open]:animate-in data-[state=open]:fade-in" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,400px)] -translate-x-1/2 -translate-y-1/2 rounded-[var(--radius)] bg-card shadow-xl outline-none data-[state=open]:animate-in data-[state=open]:fade-in">
          <div className="p-5">
            <Dialog.Title className="text-base font-medium">{pending?.title ?? ""}</Dialog.Title>
            {pending?.desc && (
              <Dialog.Description className="mt-1.5 text-sm text-muted-foreground">{pending.desc}</Dialog.Description>
            )}
            {pending?.requireText && (
              <div className="mt-4">
                <div className="mb-1.5 text-xs text-muted-foreground">
                  {t("confirm.requireHint", { text: pending.requireText })}
                </div>
                <Input
                  autoFocus
                  value={typed}
                  placeholder={pending.requireText}
                  onChange={(e) => setTyped(e.target.value)}
                />
              </div>
            )}
          </div>
          <div className="flex justify-end gap-2 bg-muted/40 p-4">
            <Button size="sm" variant="secondary" onClick={() => close(false)}>
              {pending?.cancelText ?? t("confirm.cancel")}
            </Button>
            <Button
              size="sm"
              variant={pending?.danger ? "destructive" : "default"}
              disabled={locked}
              onClick={() => close(true)}
            >
              {pending?.confirmText ?? t("confirm.ok")}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );

  return { confirm, dialog };
}
