"use client";

// 通用文字提示（自绘，替代原生 title：无 ~1s 延迟、样式随产品主题）。
//
// ⚠️ 必须 portal 到 body + position:fixed，**不能**用 absolute 挂在触发器容器里：
// 触发器常处于 overflow-y-auto 的容器内（如左侧图标栏），CSS 规范下一个轴设为 auto
// 会把另一个轴的 visible 也当成 auto，absolute 浮层会被裁掉——图标栏的 tooltip
// 就为此踩过一次坑（代码在、却看不见），最终退回了原生 title。此处不要重蹈。
//
// 用法（render-prop：不额外包 DOM，避免破坏 flex 布局）：
//   <Tooltip label="设备管理">
//     {(p) => <Link {...p} href="/devices">…</Link>}
//   </Tooltip>
// label 为空/undefined 时完全透传，不挂任何事件（如图标栏展开态无需 tip）。
import { useCallback, useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";

export type TooltipSide = "top" | "right" | "bottom" | "left";

/** hover/focus 到浮层出现的延迟（ms）。需 ≤150ms 才有"即时"感。 */
const SHOW_DELAY = 120;
const GAP = 8;

export interface TooltipTriggerProps {
  ref: (el: HTMLElement | null) => void;
  "aria-describedby"?: string;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
  onFocus: () => void;
  onBlur: () => void;
}

export function Tooltip({
  label, side = "right", children,
}: {
  label?: React.ReactNode;
  side?: TooltipSide;
  children: (props: TooltipTriggerProps) => React.ReactNode;
}) {
  const id = useId();
  const anchor = useRef<HTMLElement | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);

  const place = useCallback(() => {
    const el = anchor.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    // RTL 下逻辑方向翻转：右侧提示应出现在左侧（transform 里再 -100% 收回宽度）。
    const rtl = typeof document !== "undefined" && document.documentElement.dir === "rtl";
    const horizontal = side === "left" || side === "right";
    const toStart = side === "left" ? !rtl : rtl; // 是否贴在触发器的左边
    const top = horizontal
      ? Math.min(Math.max(r.top + r.height / 2, 16), window.innerHeight - 16)
      : side === "top" ? r.top - GAP : r.bottom + GAP;
    const left = horizontal
      ? (toStart ? r.left - GAP : r.right + GAP)
      : Math.min(Math.max(r.left + r.width / 2, 16), window.innerWidth - 16);
    setPos({ top, left });
  }, [side]);

  const open = useCallback(() => {
    clearTimeout(timer.current);
    timer.current = setTimeout(place, SHOW_DELAY);
  }, [place]);
  const close = useCallback(() => {
    clearTimeout(timer.current);
    setPos(null);
  }, []);

  useEffect(() => () => clearTimeout(timer.current), []);

  // 打开期间：Esc 关闭；缩放时重算位置；滚动直接关闭
  // （滚动会把触发器移出指针下方，而程序化滚动不保证补发 mouseleave → 浮层会残留）。
  useEffect(() => {
    if (!pos) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("keydown", onKey);
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", place);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", place);
    };
  }, [pos, place, close]);

  const setRef = useCallback((el: HTMLElement | null) => { anchor.current = el; }, []);

  if (!label) {
    return <>{children({ ref: setRef, onMouseEnter: () => {}, onMouseLeave: () => {}, onFocus: () => {}, onBlur: () => {} })}</>;
  }

  const rtl = typeof document !== "undefined" && document.documentElement.dir === "rtl";
  const horizontal = side === "left" || side === "right";
  const toStart = side === "left" ? !rtl : rtl;
  const transform = horizontal
    ? `translate(${toStart ? "-100%" : "0"}, -50%)`
    : `translate(-50%, ${side === "top" ? "-100%" : "0"})`;

  return (
    <>
      {children({
        ref: setRef,
        "aria-describedby": pos ? id : undefined,
        onMouseEnter: open,
        onMouseLeave: close,
        onFocus: open,
        onBlur: close,
      })}
      {pos && typeof document !== "undefined" && createPortal(
        <div
          id={id}
          role="tooltip"
          className="pointer-events-none fixed z-[var(--z-tooltip)] max-w-[240px] truncate rounded-field bg-foreground px-2.5 py-1.5 text-[13px] font-medium leading-snug text-background shadow-pop"
          style={{ top: pos.top, left: pos.left, transform }}
        >
          {label}
        </div>,
        document.body,
      )}
    </>
  );
}
