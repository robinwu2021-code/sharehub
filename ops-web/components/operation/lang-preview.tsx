"use client";

// 三语预览：运营在后台编辑三语内容时，按 C 端的语言与书写方向预览效果。
// 阿语整块 dir="rtl"——只看文字不看方向，发现不了标点、数字、混排英文在 RTL 下的错位。
//
// 某语言没填时，显示 C 端的真实回退行为（回退到中文），并标出「未填写，C 端将显示中文」，
// 而不是显示空白——空白会让人以为预览坏了。
import { useState } from "react";
import { Tabs } from "@/components/ui/tabs";

export type PreviewLang = "zh" | "en" | "ar";
export type LangText = { zh: string; en?: string; ar?: string };

const LANG_TABS = [
  { key: "zh", label: "中文" },
  { key: "en", label: "English" },
  { key: "ar", label: "العربية" },
];

/** 取某语言的文本；缺失时回退中文，并告知是否发生了回退。 */
export function pickLang(t: LangText, lang: PreviewLang): { text: string; fallback: boolean } {
  const v = lang === "zh" ? t.zh : t[lang];
  return v && v.trim() ? { text: v, fallback: false } : { text: t.zh, fallback: lang !== "zh" };
}

export function LangPreview({
  render,
}: {
  /** 按语言渲染 C 端样式的内容；dir 已由外层设置 */
  render: (lang: PreviewLang) => React.ReactNode;
}) {
  const [lang, setLang] = useState<PreviewLang>("zh");
  return (
    <div>
      <Tabs tabs={LANG_TABS} value={lang} onChange={(k) => setLang(k as PreviewLang)} />
      <div dir={lang === "ar" ? "rtl" : "ltr"} lang={lang} className="rounded-card bg-muted/50 p-4">
        {render(lang)}
      </div>
    </div>
  );
}

/** 回退提示：放在预览里某段文字下方。 */
export function FallbackHint({ show }: { show: boolean }) {
  if (!show) return null;
  return <p className="mt-1 txt-caption text-muted-foreground" dir="ltr">（未填写该语言，C 端将显示中文）</p>;
}
