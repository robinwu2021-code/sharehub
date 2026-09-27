// 流程与工单（/flow/）的文案，挂在总目录的 `flow` 命名空间下。
// 每页一个文件（三语同处），这里只做组装。新增页面：建文件 → 在 NS 里登记。
// 单独成目录是为了多区拆分（22/02）：flow 区独立后整个目录跟着走。
import { common } from "./common";
import { todo } from "./todo";
import { approvals } from "./approvals";
import { approvalDetail } from "./approvalDetail";
import { queue } from "./queue";
import { tickets } from "./tickets";
import { ticketDetail } from "./ticketDetail";
import { processes } from "./processes";
import { settings } from "./settings";
import { access } from "./access";

const NS = { common, todo, approvals, approvalDetail, queue, tickets, ticketDetail, processes, settings, access };

export type FlowMessages = { [K in keyof typeof NS]: (typeof NS)[K]["zh"] };
const pick = (l: "zh" | "en" | "ar") =>
  Object.fromEntries(Object.entries(NS).map(([k, v]) => [k, v[l]])) as FlowMessages;

export const flowZh = pick("zh");
export const flowEn = pick("en");
export const flowAr = pick("ar");
