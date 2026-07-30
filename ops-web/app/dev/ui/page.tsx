"use client";

// ⚠️⚠️ 写这页时的第一个坑，后来者务必先读：
// **Tailwind 4 会扫描所有源文件的字符串字面量与注释去找类名候选。**
// 所以在这个目录里写「类名示例」文案要格外小心：形如 z-中括号-var-通配符 这种
// 带通配符、又长得像类名的文本，会被当成真类名去生成 CSS，产出一条 z-index 值里带通配符的规则
// —— 非法值，直接让 globals.css 整个编译失败、**全站白屏**（不只是这一页）。
// 已经发生过一次。写示例时用省略号（--z-…）或举一个真实存在的档（--z-drawer）。
//
// ── 这页是什么 ─────────────────────────────────────────────────────────────
// 组件总览（全状态矩阵）。**dev-only 工具页**：不在 lib/nav.ts 里、不进阶段门禁、
// 不出现在任何菜单，只能靠 URL 直达。不是给运营用的。
//
// 为什么值得存在：本轮配色返工了三次，每次都要手点五六个业务页才能看到效果，
// 而且**漏掉的状态就是漏掉了** —— Badge 文字对比度只有 2.8:1（小字过不了 WCAG AA）
// 就是这么一路漏到很后面才被发现的。有这一页：验收的人一眼扫完，改动的人能截图自证。
//
// 用法：顶部四个开关（明暗 / 皮肤 / RTL / 密度）作用在 <html> 上，**刻意不写
// localStorage** —— 否则会污染使用者自己的真实设置（本会话已经因此手动改回过一次）。
// 离开本页时自动还原。
import * as React from "react";
import { PrimitiveSections } from "./primitives";
import { CompositeSections } from "./composites";
import { BusinessSections } from "./business";
import { PreviewVersionCtx } from "./probe";
import { audit, groupFindings, type AuditResult } from "./audit";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// 与 lib/stores/theme.ts 的 THEMES 一一对应（四套）。
// 曾经漏了简电青 —— 总览页少一套皮肤，等于那套皮肤的问题永远不会在这里被发现。
const SKINS = [
  { key: "mono", label: "黑白灰" },
  { key: "brand", label: "简电青" },
  { key: "blue", label: "时尚蓝" },
  { key: "purple", label: "科幻紫" },
] as const;

/** 顶部开关：直接改 <html>，离开本页还原。 */
function useRootToggles() {
  const [dark, setDark] = React.useState(false);
  const [skin, setSkin] = React.useState<string>("mono");
  const [rtl, setRtl] = React.useState(false);
  const [dense, setDense] = React.useState(false);

  // 进页时记下原值，卸载时逐项还原 —— 不碰 localStorage，用户的真实设置不受影响
  React.useEffect(() => {
    const el = document.documentElement;
    const prev = {
      dark: el.classList.contains("dark"),
      theme: el.dataset.theme,
      dir: el.dir,
      density: el.dataset.density,
    };
    return () => {
      el.classList.toggle("dark", prev.dark);
      if (prev.theme) el.dataset.theme = prev.theme; else delete el.dataset.theme;
      el.dir = prev.dir || "ltr";
      if (prev.density) el.dataset.density = prev.density; else delete el.dataset.density;
    };
  }, []);

  React.useEffect(() => { document.documentElement.classList.toggle("dark", dark); }, [dark]);
  React.useEffect(() => { document.documentElement.dataset.theme = skin; }, [skin]);
  React.useEffect(() => { document.documentElement.dir = rtl ? "rtl" : "ltr"; }, [rtl]);
  React.useEffect(() => {
    const el = document.documentElement;
    if (dense) el.dataset.density = "dense"; else delete el.dataset.density;
  }, [dense]);

  return { dark, setDark, skin, setSkin, rtl, setRtl, dense, setDense };
}

function Toggle({
  on, onClick, children,
}: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={on}
      className={cn(
        "rounded-field px-3 py-1 txt-label transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--ring-offset-bg)]",
        on ? "bg-primary text-primary-foreground" : "bg-secondary text-secondary-foreground hover:bg-accent",
      )}
    >
      {children}
    </button>
  );
}

export default function DevUiPage() {
  const t = useRootToggles();
  const rootRef = React.useRef<HTMLDivElement>(null);
  const [result, setResult] = React.useState<AuditResult | null>(null);
  // 开关一变，所有实测数值（对比度/尺寸）都要重算 —— 用一个自增版本号广播
  const [version, setVersion] = React.useState(0);

  React.useEffect(() => {
    // 等这一帧的样式生效再量，否则量到的是切换前的值
    const id = requestAnimationFrame(() => setVersion((v) => v + 1));
    return () => cancelAnimationFrame(id);
  }, [t.dark, t.skin, t.rtl, t.dense]);

  const runAudit = React.useCallback(() => {
    if (rootRef.current) setResult(audit(rootRef.current));
  }, []);

  const grouped = result ? groupFindings(result.findings) : [];

  return (
    <PreviewVersionCtx.Provider value={version}>
      <div className="pb-16">
        <header className="mb-4">
          <h1 className="txt-display">组件总览 · 全状态矩阵</h1>
          <p className="mt-1 txt-caption text-muted-foreground">
            dev-only 工具页，不在菜单里。四个开关作用于 <code>&lt;html&gt;</code>，离开本页自动还原，不写 localStorage。
          </p>
        </header>

        <div className="sticky top-0 z-[var(--z-sticky)] mb-5 flex flex-wrap items-center gap-2 rounded-card bg-card/95 p-3 shadow-[var(--card-shadow)] backdrop-blur">
          <Toggle on={t.dark} onClick={() => t.setDark(!t.dark)}>{t.dark ? "暗色" : "浅色"}</Toggle>
          <span className="mx-1 h-4 w-px bg-border" />
          {SKINS.map((s) => (
            <Toggle key={s.key} on={t.skin === s.key} onClick={() => t.setSkin(s.key)}>{s.label}</Toggle>
          ))}
          <span className="mx-1 h-4 w-px bg-border" />
          <Toggle on={t.rtl} onClick={() => t.setRtl(!t.rtl)}>RTL</Toggle>
          <Toggle on={t.dense} onClick={() => t.setDense(!t.dense)}>紧凑</Toggle>
          <div className="ms-auto flex items-center gap-2">
            {result && (
              <span className="txt-caption text-muted-foreground">
                扫 {result.scanned} 元素 / {result.focusable} 可聚焦 · 发现 {result.findings.length} 处
              </span>
            )}
            <Button size="sm" variant="secondary" onClick={runAudit}>规范体检</Button>
          </div>
        </div>

        {result && (
          <section className="mb-5 rounded-card bg-card p-5 shadow-[var(--card-shadow)]">
            <h2 className="txt-heading">规范体检结果</h2>
            <p className="mt-1 txt-caption text-muted-foreground">
              在**已渲染的 DOM** 上扫描，不是读源码 —— 组件改好了这份清单会自动变短，不需要有人回来维护手抄的问题列表。
              局限：抽屉/弹窗关着时其内容不在 DOM 里（打开后重新体检）；class 字符串判断不了「这个尺寸是不是刻意的」，
              所以结论是**线索不是判决**。
            </p>
            {grouped.length === 0 ? (
              <div className="mt-3 rounded-field bg-success-tint px-3 py-2 txt-caption text-success-ink">
                本次扫描未发现违规。
              </div>
            ) : (
              <ul className="mt-3 space-y-1.5">
                {grouped.map((g) => (
                  <li key={`${g.comp}/${g.rule}`} className="rounded-field bg-muted px-3 py-2 txt-caption">
                    <span className="font-bold">{g.comp}</span>
                    <span className="mx-1.5 text-muted-foreground">·</span>
                    <span>{g.rule}</span>
                    <span className="ms-1.5 rounded-chip bg-destructive-tint px-1.5 py-0.5 text-destructive-ink">
                      {g.count}
                    </span>
                    <div className="mt-1 text-muted-foreground">{g.details.slice(0, 4).join(" · ")}</div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        <div ref={rootRef} className="space-y-5">
          <PrimitiveSections />
          <CompositeSections />
          <BusinessSections />
        </div>
      </div>
    </PreviewVersionCtx.Provider>
  );
}
