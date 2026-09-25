"use client";

// 代理清退进度抽屉（运营核心流程 F3）：步骤条 + 当前步门禁 + 推进。
//
// 三条规矩：
// 1. **推进按钮在门禁全过之前是禁用的**，并说清还差几项 —— 服务端还会再校验一遍（防并发与绕过前端）。
// 2. 门禁的「去处理」链接先经 exitFixHref 改写：后端给的路由运营端并不存在（见 lib/agent-exit-href.ts）。
// 3. 最后一步（关闭账号并归档）不可逆，走 requireText；前两步可逆性也很低，至少走普通确认。
import { useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import {
  AGENT_EXIT_TRANSITIONS, agentExitActionOf,
  type AgentExit, type AgentExitStatus, type Checklist,
} from "@/lib/types";
import { exitFixHref } from "@/lib/agent-exit-href";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Timeline, type TimelineItem } from "@/components/ui/timeline";
import { ErrorState } from "@/components/ui/misc";
import { Notice } from "@/components/ui/notice";
import { StateActions } from "@/components/state-actions";
import { GateChecklist } from "@/components/gate-checklist";
import { RefLink } from "@/components/ref-link";

export const AGENT_EXIT_STATUS: StatusMap<AgentExitStatus> = {
  RECLAIMING: { label: "收回资产中", tone: "warning" },
  SETTLING: { label: "结清中", tone: "warning" },
  CLOSING: { label: "待关闭", tone: "info" },
  CLOSED: { label: "已清退", tone: "muted" },
};

const STEPS: { key: AgentExitStatus; label: string }[] = [
  { key: "RECLAIMING", label: "收回资产" },
  { key: "SETTLING", label: "结清" },
  { key: "CLOSING", label: "关闭账号" },
  { key: "CLOSED", label: "已清退" },
];

/** 每一步在做什么、门禁在拦什么 —— 让操作人知道这一步为什么不能跳。 */
const STEP_HINT: Record<AgentExitStatus, string> = {
  RECLAIMING: "先把站点、站点责任、机柜、未完结工单全部收回 —— 资产没收回就结账，结完才发现还有柜子在它手里产生分润。",
  SETTLING: "把分润出账、结算单付清、在途提现办结。这一步允许该代理提现与打款（其余停用期间冻结），否则钱永远结不清。",
  CLOSING: "关闭将停用该代理全部登录账号并归档代理档案，不可恢复。系统里没有代理保证金，保证金如有需线下退还。",
  CLOSED: "清退已完成：账号已停用、代理已归档。",
};

function timelineOf(e: AgentExit): TimelineItem[] {
  const rows: TimelineItem[] = [];
  if (e.startedAt) rows.push({ key: "start", badge: { label: "发起清退", tone: "warning" }, meta: `${fmtTime(e.startedAt)} · ${e.startedBy ?? "-"}`, text: e.reason ? `原因：${e.reason}（发起即停用）` : "发起即停用" });
  if (e.reclaimedAt) rows.push({ key: "reclaimed", badge: { label: "资产已收回", tone: "outline" }, meta: fmtTime(e.reclaimedAt) });
  if (e.settledAt) rows.push({ key: "settled", badge: { label: "已结清", tone: "outline" }, meta: fmtTime(e.settledAt) });
  if (e.closedAt) rows.push({ key: "closed", badge: { label: "已关闭", tone: "muted" }, meta: `${fmtTime(e.closedAt)} · ${e.closedBy ?? "-"}` });
  return rows;
}

export function AgentExitDrawer({ exitNo, onClose }: { exitNo: string | null; onClose: () => void }) {
  const qc = useQueryClient();
  const exitQ = useQuery({
    queryKey: ["agent-exit", exitNo],
    queryFn: () => api.getAgentExit(exitNo!),
    enabled: !!exitNo,
    retry: false,
  });
  const gateQ = useQuery({
    queryKey: ["agent-exit-gate", exitNo, exitQ.data?.status],
    queryFn: () => api.agentExitGate(exitNo!),
    enabled: !!exitNo && !!exitQ.data,
  });
  // 去处链接改写成运营端真实路由；allPassed 仍用服务端给的，不在前端重算
  const gate: Checklist | undefined = useMemo(
    () => gateQ.data && { ...gateQ.data, items: gateQ.data.items.map((i) => ({ ...i, fixHref: exitFixHref(i.fixHref) })) },
    [gateQ.data],
  );

  const advance = useMutation({
    mutationFn: (no: string) => api.advanceAgentExit(no),
    onSuccess: (e) => {
      notify.success(e.status === "CLOSED" ? `代理 ${e.agentNo} 已清退：账号已停用、档案已归档` : `已推进到「${AGENT_EXIT_STATUS[e.status].label}」`);
      for (const k of [["agent-exit"], ["agent-exit-gate"], ["agents"], ["agent-accounts"]]) qc.invalidateQueries({ queryKey: k });
    },
  });

  const e = exitQ.data;
  const action = e ? agentExitActionOf(e.status) : null;
  const pending = gate ? gate.items.filter((i) => !i.passed).length : 0;

  return (
    <Drawer
      open={!!exitNo}
      onOpenChange={(o) => !o && onClose()}
      title="代理清退进度"
      desc="收回资产 → 结清 → 关闭账号，每一步门禁全部通过才能推进"
      width="w-[640px]"
    >
      {exitQ.error ? (
        <ErrorState error={exitQ.error} onRetry={() => exitQ.refetch()} />
      ) : !e ? (
        <div className="txt-caption text-muted-foreground">加载中…</div>
      ) : (
        <div className="space-y-5">
          <DetailHeader
            no={e.exitNo}
            title={<>清退代理 <RefLink kind="agent" no={e.agentNo} /></>}
            badge={<StatusBadge map={AGENT_EXIT_STATUS} value={e.status} />}
            meta={`发起 ${fmtTime(e.startedAt)} · ${e.startedBy ?? "-"}`}
            stepper={<StatusStepper steps={STEPS} current={e.status} />}
            actions={action && (
              <StateActions actions={[{
                key: "advance",
                label: AGENT_EXIT_TRANSITIONS[action].label,
                perm: "agent:agent:update",
                primary: true,
                danger: action === "close",
                blockedReason: gateQ.isLoading ? "正在检查门禁…"
                  : gate && !gate.allPassed ? `门禁还差 ${pending} 项，逐项处理后再推进` : null,
                confirm: action === "close"
                  ? {
                    title: `关闭并归档代理 ${e.agentNo}`,
                    desc: "将停用该代理全部登录账号并归档代理档案，不可恢复。请输入代理编号确认。",
                    confirmText: "关闭并归档",
                    requireText: e.agentNo,
                  }
                  : {
                    title: AGENT_EXIT_TRANSITIONS[action].label,
                    desc: action === "reclaimed"
                      ? "确认站点、责任、机柜与工单都已收回。进入结清后，该代理可以提现与打款，用于把账结清。"
                      : "确认分润已出账、结算单已付清、提现已办结。进入关闭后只剩「停用账号并归档」一步。",
                    confirmText: "确认推进",
                  },
                onRun: () => advance.mutateAsync(e.exitNo),
              }]} />
            )}
          />

          <Notice className="mb-0">{STEP_HINT[e.status]}</Notice>

          <section className="space-y-2">
            <div className="txt-strong">当前步门禁</div>
            <GateChecklist
              data={gate}
              loading={gateQ.isLoading}
              passedHint={e.status === "CLOSED" ? undefined : "本步门禁已全部通过，可以推进"}
              blockedHint="还差 {n} 项 —— 点每项下的「去处理」逐条收口，处理完回到这里刷新"
            />
            {gateQ.error ? <ErrorState error={gateQ.error} onRetry={() => gateQ.refetch()} /> : null}
          </section>

          <section className="space-y-2">
            <div className="txt-strong">过程</div>
            <Field label="清退原因">{e.reason ?? <span className="text-muted-foreground">未填</span>}</Field>
            <Timeline items={timelineOf(e)} empty="还没有进展记录" />
          </section>
        </div>
      )}
    </Drawer>
  );
}
