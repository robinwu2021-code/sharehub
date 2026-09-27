/**
 * 公告的本地化取值（后端一条公告有 title/titleEn/titleAr 与 content 三语三列）。
 *
 * **两处共用这一份**：首页公告条与消息页。此前只有消息页按语言挑，首页公告条直接用
 * `notice.title`（中文那一列）—— 英文 / 阿语界面的首页顶着一条中文公告，点进去却是英文
 * （Android 模拟器实测）。规则各写一遍，迟早又分叉。
 *
 * 缺哪种语言就按「英文 → 中文」回落，不显示空串。
 */
import type { Lang, Notice } from "@/types";

function pick(lang: Lang, zh: string, en: string, ar: string): string {
  if (lang === "ar") return ar || en || zh;
  if (lang === "en") return en || zh;
  return zh || en;
}

export const noticeTitle = (n: Notice, lang: Lang) => pick(lang, n.title, n.titleEn, n.titleAr);
export const noticeBody = (n: Notice, lang: Lang) => pick(lang, n.content, n.contentEn, n.contentAr);
