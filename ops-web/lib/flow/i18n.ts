"use client";

// 流程与工单页面的取文案入口：`const { t, tc, fmt } = useFlowT("todo")`。
//   t("title")          → flow.todo.title（本页命名空间）
//   tc("sla.left", …)   → flow.common.sla.left（共用）
//   fmt.dur(分钟) / fmt.when(ms) / fmt.span(分钟) / fmt.fail(e) —— 随语言变的格式化
// key 有类型：拼错编译不过（比运行时回退成 key 原文早发现）。
import { useI18n, type TFn } from "@/lib/i18n";
import type { FlowMessages } from "@/lib/i18n/messages/flow";
import { isValidationError } from "./errors";
import { fmtShort } from "./present";

type Leaves<T> = { [K in keyof T & string]: T[K] extends string ? K : `${K}.${Leaves<T[K]>}` }[keyof T & string];
type Params = Record<string, string | number>;
export type FlowNs = keyof FlowMessages;
export type FlowKey<N extends FlowNs> = Leaves<FlowMessages[N]>;
export type CommonKey = FlowKey<"common">;

/** 格式化（纯函数，便于测试）：只依赖一个取 common 文案的函数 */
export function makeFlowFmt(tc: (k: CommonKey, p?: Params) => string) {
  const pad = (n: number) => String(n).padStart(2, "0");
  /** 分钟 → 「1 天 3 小时」「2 小时 5 分」「40 分钟」（取绝对值；正负由调用方表达） */
  const dur = (min: number) => {
    const m = Math.abs(Math.round(min));
    if (m >= 1440) { const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60); return h ? tc("dur.dh", { d, h }) : tc("dur.d", { n: d }); }
    if (m >= 60) { const h = Math.floor(m / 60), r = m % 60; return r ? tc("dur.hm", { h, m: r }) : tc("dur.h", { n: h }); }
    return tc("dur.m", { n: m });
  };
  return {
    dur,
    /** 配置里的整数时长：「30 分钟」「2 小时」「1 天」 */
    span: (min: number) => (min % 1440 === 0 ? tc("dur.d", { n: min / 1440 }) : min % 60 === 0 ? tc("dur.h", { n: min / 60 }) : tc("dur.m", { n: min })),
    short: fmtShort,
    /** 今天只显示时刻，其余显示月-日 时:分 */
    when: (ms: number, now: number = Date.now()) => {
      const d = new Date(ms);
      return d.toDateString() === new Date(now).toDateString() ? tc("today", { t: `${pad(d.getHours())}:${pad(d.getMinutes())}` }) : fmtShort(ms);
    },
    /** 失败提示：422 时把逐条错误拼进去（表单抽屉没有按服务端路径挂错误的位置） */
    fail: (e: Error) => (isValidationError(e) ? `${e.message}${tc("colon")}${e.errors.map((x) => x.error).join(tc("listSep"))}` : e.message),
    /** 「A · B · C」，空项跳过 */
    join: (...parts: (string | undefined | null | false)[]) => parts.filter(Boolean).join(tc("sep")),
  };
}
export type FlowFmt = ReturnType<typeof makeFlowFmt>;

export const flowTc = (t: TFn) => (k: CommonKey, p?: Params) => t(`flow.common.${k}`, p);

export function useFlowT<N extends FlowNs>(ns: N) {
  const { t: raw, locale } = useI18n();
  const tc = flowTc(raw);
  return {
    locale,
    t: (k: FlowKey<N>, p?: Params) => raw(`flow.${ns}.${k}`, p),
    tc,
    fmt: makeFlowFmt(tc),
  };
}
