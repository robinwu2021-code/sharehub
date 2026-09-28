"use client";

// 下拉选项的取数钩子（成员、工单类型、成员属性、流程、提交人）。
// 选项是**服务端数据**：换语言要重取（名称按 Accept-Language 给），所以 key 带 locale；
// 放在 FLOW_KEY 之下 —— 写操作整体失效时一并刷新（新建模板后流程筛选项立刻有它）。
import { useQuery } from "@tanstack/react-query";
import { useLocaleStore } from "@/lib/stores/locale";
import { flow, FLOW_KEY } from "./client";

const useLoc = () => useLocaleStore((s) => s.locale);

/** 改派候选。`truncated` = 服务端还有更多（只列了前一页）—— 页面要明说，不静默截断 */
export function useMembers() {
  const locale = useLoc();
  const q = useQuery({ queryKey: [...FLOW_KEY, "opt", "members", locale], queryFn: flow.members, staleTime: 60_000 });
  const list = q.data?.list ?? [];
  return { list, truncated: !!q.data && q.data.total > list.length, total: q.data?.total ?? 0, isLoading: q.isLoading };
}

const opt = <T,>(name: string, fn: () => Promise<T[]>) => () => {
  const locale = useLoc();
  return useQuery({ queryKey: [...FLOW_KEY, "opt", name, locale], queryFn: fn, staleTime: 60_000 }).data ?? [];
};

export const useTicketTypes = opt("ticket-types", flow.ticketTypes);
export const usePartyOptions = opt("party", flow.partyOptions);
export const useProcessOptions = opt("processes", flow.processOptions);
export const useRequesterOptions = opt("requesters", flow.requesterOptions);
