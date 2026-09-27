// 流程与工单平台的客户端 —— 页面唯一的取数入口。
//
// 现在转给 mock（lib/flow/mock-db.ts）；对接 opskit-svc 时**只改这个文件**：
// 方法名、参数、出参与 21/06 接口设计一一对应，注释里标了对应的端点。
// 基址：经宿主同源反代 /api/opskit/v1；运行时资源在 /workspaces/{ws}/ 下，ShareHub 场景 ws = ext:{租户号}。
// 页面不直接 import mock-db（architecture: 页面只认 client）。
import * as db from "./mock-db";
import type { Member, Named, Page, TicketQuery, TicketRow, TicketStat, TodoCount, TodoItem, TodoQuery } from "./types";

/** 模拟网络往返：让加载态、按钮 loading 在 mock 下也真实出现 */
const tick = <T,>(f: () => T) => new Promise<T>((resolve, reject) => {
  setTimeout(() => { try { resolve(f()); } catch (e) { reject(e); } }, 120);
});

export const flow = {
  /** POST /workspaces/{ws}/todos/query */
  todos: (q: TodoQuery): Promise<Page<TodoItem>> => tick(() => db.queryTodos(q)),
  /** POST /workspaces/{ws}/todos/count */
  todoCount: (): Promise<TodoCount> => tick(() => db.countTodos()),
  /** POST /workspaces/{ws}/tickets/query */
  tickets: (q: TicketQuery): Promise<Page<TicketRow>> => tick(() => db.queryTickets(q)),
  /** POST /workspaces/{ws}/ticket-stats/summary */
  ticketStats: (): Promise<TicketStat[]> => tick(() => db.ticketStats()),
  /** POST /workspaces/{ws}/tickets/{no}/claim */
  claim: (no: string): Promise<TicketRow> => tick(() => db.claimTicket(no)),
  /** POST /workspaces/{ws}/tickets/{no}/transitions/{code} */
  transition: (no: string, code: string): Promise<TicketRow> => tick(() => db.transitionTicket(no, code)),
  /** POST /workspaces/{ws}/tickets/reassign */
  reassign: (nos: string[], to: Member, reason: string): Promise<TicketRow[]> =>
    tick(() => db.reassignTickets(nos, to, reason)),
  /** 改派候选（接后端后：成员 / 组接口） */
  assigneeOptions: (): Member[] => db.ASSIGNEE_OPTIONS,
  /** 工单类型（接后端后：GET /workspaces/{ws}/ticket-types） */
  ticketTypes: (): Named[] => db.TICKET_TYPES,
  /** 受理方筛选项：成员属性 partyType 的取值（接后端后：成员属性定义） */
  partyOptions: (): Named[] => db.PARTY_OPTIONS,
};

/** react-query 的 key 前缀：任何写操作后整体失效，待办角标、统计卡、列表一起刷新 */
export const FLOW_KEY = ["flow"] as const;
