"use client";

// 经营看板 › 待办中心 · 告警待办（方案 §8.2a）。
//
// 合作、经营、资金、部分资产域告警的首选处置是**待办**：BD 看到「合同 60 天内到期」「站点无合同在营业」，
// 财务看到「分成方收不了款」「退款失败」。每条待办带来源告警 RefLink，办结待办即推进告警 ——
// **这样 BD 和财务不必进告警中心，也不需要新增权限码**（沿用 dashboard:todo:read）。
//
// 「我的」= 派给我本人或我这个岗位的（后端 mine=true 按 assigneeNo / roleCode 收敛）；
// 管理员自己的岗位通常没有待办，所以给一个「全部岗位」视图，否则他看到的永远是空。
import * as React from "react";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/hooks/use-can";
import { usePaging } from "@/lib/hooks/use-paging";
import { notify } from "@/lib/notify";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Drawer, Field } from "@/components/ui/drawer";
import { Textarea } from "@/components/ui/textarea";
import { StatusBadge } from "@/components/ui/status-badge";
import { HelpNote } from "@/components/ui/help-note";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { segmentedTrackClass, segmentedItemClass } from "@/components/ui/segmented";
import { RefLink } from "@/components/ref-link";
import { ALARM_TODO_STATUS, roleLabel } from "@/components/alarm/alarm-maps";
import { fmtTime } from "@/lib/utils";
import type { AlarmTodo, AlarmTodoStatus } from "@/lib/types";

type Scope = "mine" | "all";

const PAGE = 8;

export function AlarmTodoPanel() {
  const allow = useCan();
  const qc = useQueryClient();
  const [scope, setScope] = React.useState<Scope>("mine");
  const [status, setStatus] = React.useState<AlarmTodoStatus>("OPEN");
  // 看板卡片里一页 8 条：够扫一眼，又不把看板撑成列表页
  const paging = usePaging(PAGE);
  const { page, setPage } = paging;
  const [doing, setDoing] = React.useState<AlarmTodo | null>(null);
  const [note, setNote] = React.useState("");
  const can = allow("dashboard:todo:read");

  const count = useQuery({ queryKey: ["alarmTodos", "count"], queryFn: () => api.alarmTodoCount(), enabled: can });
  const q = useQuery({
    queryKey: ["alarmTodos", "list", scope, status, page],
    queryFn: () => api.listAlarmTodos({ mine: scope === "mine", status, page, size: PAGE }),
    enabled: can,
    placeholderData: keepPreviousData,
  });
  const done = useMutation({
    mutationFn: (v: { todoNo: string; note: string }) => api.doneAlarmTodo(v.todoNo, v.note.trim() || undefined),
    onSuccess: (t) => {
      notify.success(`待办 ${t.todoNo} 已办结`);
      qc.invalidateQueries({ queryKey: ["alarmTodos"] });
      qc.invalidateQueries({ queryKey: ["alarm"] });
      setDoing(null);
    },
  });

  if (!can) return null;
  const list = q.data?.list ?? [];
  const total = q.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE));
  const reset = <T,>(fn: (v: T) => void) => (v: T) => { fn(v); paging.reset(); };

  return (
    <Card className="mt-6">
      <CardHeader className="flex flex-row flex-wrap items-center gap-2">
        <CardTitle className="flex items-center gap-2">
          告警待办
          {count.data && count.data.open > 0 && (
            <span className="rounded-chip bg-warning-tint px-2 txt-caption text-warning-ink tabular-nums" aria-label={`我有 ${count.data.open} 条待办`}>
              {count.data.open}
            </span>
          )}
        </CardTitle>
        <HelpNote>
          <p>合作、经营、资金类告警（合同快到期、站点无合同在营业、退款失败、分成方收不了款…）不派工单，落到这里由对应岗位办理。</p>
          <p className="mt-1">办结后：告警条件已不成立 → 来源告警自动关闭；仍成立 → 下一轮判定会重新生成待办（不是办完就算了）。</p>
          <p className="mt-1">角标是「我的」未办结数：派给我本人或我这个岗位的。</p>
        </HelpNote>
        <div className="ms-auto flex flex-wrap gap-2">
          <div className={segmentedTrackClass()} role="group" aria-label="待办范围">
            {([["mine", "我的"], ["all", "全部岗位"]] as const).map(([k, l]) => (
              <button key={k} type="button" aria-pressed={scope === k} className={segmentedItemClass(scope === k, "px-3 py-1 text-sm")}
                onClick={() => reset(setScope)(k)}>{l}</button>
            ))}
          </div>
          <div className={segmentedTrackClass()} role="group" aria-label="待办状态">
            {(["OPEN", "DONE", "CANCELLED"] as const).map((k) => (
              <button key={k} type="button" aria-pressed={status === k} className={segmentedItemClass(status === k, "px-3 py-1 text-sm")}
                onClick={() => reset(setStatus)(k)}>{ALARM_TODO_STATUS[k].label}</button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {q.isLoading && <Skeleton className="h-24" />}
        {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {!q.isLoading && !q.error && list.length === 0 && (
          <p className="txt-body text-muted-foreground">
            {status !== "OPEN" ? "这个范围里还没有办结 / 撤销的待办。"
              : scope === "mine" ? "你的岗位眼下没有告警待办。合同到期、站点无合同、退款失败这类告警出现时会落到对应岗位；想看别的岗位，切到「全部岗位」。"
              : "所有岗位都没有未办的告警待办——合作、经营、资金类告警眼下都已处理完。"}
          </p>
        )}
        {list.length > 0 && (
          <ul className="divide-y divide-border">
            {list.map((t) => (
              <li key={t.todoNo} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2.5">
                <div className="min-w-0 flex-1">
                  <div className="txt-body">{t.title}</div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-x-2 txt-caption text-muted-foreground">
                    <span>{roleLabel(t.roleCode)}{t.assigneeNo ? ` · ${t.assigneeNo}` : ""}</span>
                    <span>· 来源告警 <RefLink kind="alarm" no={t.alarmNo} /></span>
                    {t.siteNo && <span>· 站点 <RefLink kind="site" no={t.siteNo} /></span>}
                    <span className="tabular-nums">· {fmtTime(t.createdAt)}</span>
                    {t.doneAt && <span className="tabular-nums">· {t.doneBy ?? "-"} 于 {fmtTime(t.doneAt)}{t.doneNote ? `：${t.doneNote}` : ""}</span>}
                  </div>
                </div>
                {t.status === "OPEN"
                  ? <Button size="sm" variant="outline" onClick={() => { setDoing(t); setNote(""); }}>办结</Button>
                  : <StatusBadge map={ALARM_TODO_STATUS} value={t.status} />}
              </li>
            ))}
          </ul>
        )}
        {total > PAGE && (
          <div className="mt-3 flex items-center justify-end gap-2 txt-caption text-muted-foreground">
            <span className="tabular-nums">共 {total} 条 · {page}/{pages}</span>
            <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Button>
            <Button size="sm" variant="outline" disabled={page >= pages} onClick={() => setPage(page + 1)}>下一页</Button>
          </div>
        )}
      </CardContent>

      {/* 办结确认：抽屉本身就是确认（R4），说明选填 */}
      <Drawer
        open={!!doing}
        onOpenChange={(o) => !o && setDoing(null)}
        title={`办结待办 ${doing?.todoNo ?? ""}`}
        desc="办结后若告警条件已不成立，来源告警随之关闭；仍成立则下一轮会重新生成待办"
        footer={doing && (
          <>
            <Button variant="outline" onClick={() => setDoing(null)}>取消</Button>
            <Button disabled={done.isPending} onClick={() => done.mutate({ todoNo: doing.todoNo, note })}>确认办结</Button>
          </>
        )}
      >
        {doing && (
          <>
            <Field label="待办">{doing.title}</Field>
            <Field label="来源告警"><RefLink kind="alarm" no={doing.alarmNo} /></Field>
            <Field label="办理说明（选填）">
              <Textarea value={note} rows={3} placeholder="做了什么：已联系场地方续签 / 已补收款账户……" onChange={(v) => setNote(v)} />
            </Field>
          </>
        )}
      </Drawer>
    </Card>
  );
}
