/**
 * 公告与站内信的本地化取值（三语三列，[db-design §1.5]）。
 *
 * **三处共用这一份**：首页公告条、消息页的公告栏、消息页的收件箱。此前只有消息页按语言挑，首页公告条直接用
 * `notice.title`（中文那一列）—— 英文 / 阿语界面的首页顶着一条中文公告，点进去却是英文
 * （Android 模拟器实测）。规则各写一遍，迟早又分叉。
 *
 * 缺哪种语言就按「英文 → 中文」回落，不显示空串。
 */
import type { Lang, MessageItem, Notice } from "@/types";

function pick(lang: Lang, zh: string, en: string, ar: string): string {
  if (lang === "ar") return ar || en || zh;
  if (lang === "en") return en || zh;
  return zh || en;
}

export const noticeTitle = (n: Notice, lang: Lang) => pick(lang, n.title, n.titleEn, n.titleAr);
export const noticeBody = (n: Notice, lang: Lang) => pick(lang, n.content, n.contentEn, n.contentAr);

/*
 * 站内信同样是三语三列（V133 补齐）。此前后端这张表**一条消息都没有**，
 * 所以「语言不对」这件事还没机会表现出来 —— 补写入方的同时把端上这一步也接上，
 * 免得第一批消息就在阿语界面上显示中文。
 */
export const messageTitle = (m: MessageItem, lang: Lang) => pick(lang, m.title, m.titleEn, m.titleAr);
export const messageBody = (m: MessageItem, lang: Lang) => pick(lang, m.body, m.bodyEn, m.bodyAr);
