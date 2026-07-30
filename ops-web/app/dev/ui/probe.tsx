"use client";

// 对比度探针：把「实测 WCAG 对比度」贴在组件旁边。
//
// 这是本页存在的直接原因之一 —— Badge 的 `#12b76a` 落在浅绿底上只有 ~2.8:1，
// 小字过不了 AA，靠肉眼看了很多轮都没发现。数字必须**运行时量真实 DOM**：
// 明/暗、三套皮肤、RTL 各一套值，写死就会说谎。
import * as React from "react";
import { measure, aaThreshold, type Measured } from "./color";
import { cn } from "@/lib/utils";

/** 任一全局开关（明暗/皮肤/RTL/密度）变化时自增，探针据此重量。 */
export const PreviewVersionCtx = React.createContext(0);

export function useMeasured(ref: React.RefObject<HTMLElement | null>, pick?: (root: HTMLElement) => Element | null) {
  const version = React.useContext(PreviewVersionCtx);
  const [res, setRes] = React.useState<{ m: Measured; need: number } | null>(null);

  // pick 是每次渲染新建的箭头函数：放进 deps 会「量→setState→重渲染→再量」死循环。
  // 存 ref，effect 只认 version。
  const pickRef = React.useRef(pick);
  pickRef.current = pick;

  React.useEffect(() => {
    let raf2 = 0;
    // 双 rAF：等换肤/明暗切换后的样式真正生效再量，否则量到上一帧的旧色
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        const root = ref.current;
        if (!root) return;
        const p = pickRef.current;
        const el = p ? p(root) : root.firstElementChild;
        if (!el) return;
        setRes({ m: measure(el), need: aaThreshold(el) });
      });
    });
    return () => { cancelAnimationFrame(raf1); cancelAnimationFrame(raf2); };
  }, [ref, version]);

  return res;
}

/** 对比度读数徽标。低于 AA 阈值标红 —— 这就是当初漏掉的那类问题。 */
export function Ratio({ res }: { res: { m: Measured; need: number } | null }) {
  if (!res) return <span className="text-[10px] text-muted-foreground">量取中…</span>;
  const { m, need } = res;
  if (m.ratio == null) {
    return <span className="text-[10px] text-muted-foreground">色值语法无法解析（{m.fg}）</span>;
  }
  const pass = m.ratio >= need;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-chip px-2 py-0.5 text-[10px] font-bold tabular-nums",
        pass ? "bg-success-tint text-success-ink" : "bg-destructive-tint text-destructive-ink",
      )}
      title={`前景 ${m.fg} / 实际背景 ${m.bg}；AA 阈值 ${need}:1`}
    >
      {m.ratio.toFixed(2)}:1 {pass ? "AA 通过" : `低于 AA（需 ${need}）`}
    </span>
  );
}

/**
 * 包一层量一层：`<Probe>` 的**第一个子 DOM 节点**就是被测对象。
 * 传 pick 可以指定测更深的节点（如测按钮里的文字）。
 */
export function Probe({
  children, pick, className,
}: {
  children: React.ReactNode;
  pick?: (root: HTMLElement) => Element | null;
  className?: string;
}) {
  const ref = React.useRef<HTMLDivElement>(null);
  const res = useMeasured(ref, pick);
  return (
    <div className={cn("flex flex-col items-start gap-1", className)}>
      <div ref={ref} className="contents">{children}</div>
      <Ratio res={res} />
    </div>
  );
}
