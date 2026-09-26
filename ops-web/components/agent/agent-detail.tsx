"use client";

// 代理商详情抽屉（`/agents?no=AG001`）：档案 + 停用影响 + 运维考核 + 清退入口。
//
// 状态只由这里的动作改（规则 R1）：停用 / 恢复 / 发起清退。编辑档案的抽屉里没有状态下拉。
//
// **停用前先给影响预览**（方案 §七 S-AGT-02）：停用不是「改个字段」——
// 名下未完结工单会被改派平台运维、提现冻结（申请 / 通过 / 打款都被拒）、不能再划拨。
// 不写明数量就让人点确认，等于让他签一份没看过的单子。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import type { Agent, AgentStatus, AgentType, WithdrawalStatus } from "@/lib/types";
import { useCan } from "@/lib/hooks/use-can";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { notify } from "@/lib/notify";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { SummaryCard } from "@/components/ui/summary-card";
import { Tabs } from "@/components/ui/tabs";
import { Notice } from "@/components/ui/notice";
import { ErrorState } from "@/components/ui/misc";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { StateActions } from "@/components/state-actions";
import { AgentOpsAssessments } from "./agent-ops-assessments";
import { AGENT_EXIT_STATUS } from "./agent-exit-drawer";

export const AGENT_STATUS: StatusMap<AgentStatus> = {
  ENABLED: { label: "启用", tone: "success" },
  SUSPENDED: { label: "停用", tone: "muted" },
};
const AGENT_TYPE_LABEL: Record<AgentType, string> = { AGENT: "代理商", CITY_PARTNER: "城市合伙人" };
/** 归档不是 AgentStatus 的一个值（它是 archivedAt 时间戳），单独一张映射，徽标与状态并排显示。 */
const ARCHIVED: StatusMap<"ARCHIVED"> = { ARCHIVED: { label: "已归档", tone: "outline" } };

/** 未完结工单口径（与后端改派 / 清退门禁同一组状态）。 */
const OPEN_WO = "CREATED,DISPATCHED,ACCEPTED,PROCESSING";
const IN_FLIGHT_WD: WithdrawalStatus[] = ["APPLY", "AUDIT", "PAYING"];
/**
 * 按编号搜代理时取的条数：关键词是模糊匹配，AG001 也会命中 AG0010…，要能把本尊捞进第一页。
 * 在途提现按名字取一页再按编号精确过滤，一页够看「有没有、大概几笔」—— 这是预览，不是对账。
 */
const LOOKUP_SIZE = 50;
/** 一个代理的在途提现不会有一百笔；状态得在前端筛（端点的 status 只收一个值）。 */
const IMPACT_SCAN_SIZE = 100;

/** 停用影响预览：各项独立取数，缺权限的那项说明看不到，而不是显示成 0 —— 0 是一个会被当真的数。 */
function useSuspendImpact(agent: Agent | undefined, enabled: boolean) {
  const allow = useCan();
  const canWo = allow("workorder:wo:read");
  const canWd = allow("finance:withdrawal:read");
  const canAssign = allow("agent:scope:assign");
  const no = agent?.agentNo ?? "";
  const wo = useQuery({
    queryKey: ["agent-impact-wo", no],
    queryFn: () => api.listWorkOrders({ page: 1, size: 1, assigneeNo: no, status: OPEN_WO }),
    enabled: enabled && !!agent && canWo,
  });
  // payeeNo 现在由后端精确筛（2026-09-26）。此前后端静默忽略这个参数，只好按
  // keyword = 代理名取一页再在页内按编号剔——那会**丢行**：翻页是按名字翻的，
  // 同名主体（种子里就有两个 North Hub）把页填满后，本代理落在页外的单子根本取不回来。
  // 状态还得在这里筛：端点的 status 只收一个值，而「在途」是好几个状态。
  const wd = useQuery({
    queryKey: ["agent-impact-wd", no],
    queryFn: () => api.listWithdrawals({ page: 1, size: IMPACT_SCAN_SIZE, payeeNo: no }),
    select: (d) => d.list.filter((w) => IN_FLIGHT_WD.includes(w.status)).length,
    enabled: enabled && !!agent && canWd,
  });
  // 名下资产从「可划拨资产池」按当前归属数（划拨汇总那个读模型后端还是空实现，会永远给 0）
  const assets = useQuery({
    queryKey: ["agent-impact-assets", no],
    queryFn: () => api.listAssignableAssets({ page: 1, size: UNPAGED_SIZE, agentNo: no }),
    select: (d) => ({
      cabinetCount: d.list.filter((a) => a.assetType === "CABINET" && a.currentAgentNo === no).length,
      siteCount: d.list.filter((a) => a.assetType === "SITE" && a.currentAgentNo === no).length,
    }),
    enabled: enabled && !!agent && canAssign,
  });
  const fmt = (ok: boolean, loading: boolean, n: number | undefined, perm: string) =>
    !ok ? `无权限查看（${perm}）` : loading ? "统计中…" : String(n ?? 0);
  return {
    woText: fmt(canWo, wo.isLoading, wo.data?.total, "workorder:wo:read"),
    wdText: fmt(canWd, wd.isLoading, wd.data, "finance:withdrawal:read"),
    cabText: fmt(canAssign, assets.isLoading, assets.data?.cabinetCount, "agent:scope:assign"),
    siteText: fmt(canAssign, assets.isLoading, assets.data?.siteCount, "agent:scope:assign"),
  };
}

export function AgentDetailDrawer({
  agentNo, exitNo, onClose, onOpenExit, onEdit,
}: {
  agentNo: string | null;
  /** URL 上带着的清退单号（`?exit=`）。后端没有「按代理查清退单」的端点，单号只能从发起结果或 URL 来。 */
  exitNo: string | null;
  onClose: () => void;
  onOpenExit: (exitNo: string) => void;
  onEdit: (a: Agent) => void;
}) {
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const [tab, setTab] = useState<"overview" | "assessments">("overview");
  const [exitForm, setExitForm] = useState<{ reason: string } | null>(null);
  const [lookup, setLookup] = useState("");

  // 后端没有 GET /api/agent/agents/{no}：按编号搜一页再精确匹配（含已归档 —— 清退关闭后代理就是归档态）
  const agentQ = useQuery({
    queryKey: ["agents", "detail", agentNo],   // 挂在 ["agents"] 下：页面上「配置」保存后作废 ["agents"] 就连带刷新详情
    queryFn: () => api.listAgents({ page: 1, size: LOOKUP_SIZE, keyword: agentNo!, showArchived: true }),
    select: (d) => d.list.find((a) => a.agentNo === agentNo),
    enabled: !!agentNo,
  });
  const agent = agentQ.data;
  /*
   * 在途清退单**按代理号直接查**（后端 2026-09-26 起提供）。
   * 此前只能从 URL 上的 `?exit=` 拿单号 —— 那个号只在「发起」那一次的响应里出现过，
   * 刷新页面、换个人看就再也找不到，于是「清退中不许恢复」这条闸在界面上时有时无。
   */
  const openExitQ = useQuery({
    queryKey: ["agent-open-exit", agentNo],
    queryFn: () => api.getAgentOpenExit(agentNo!),
    enabled: !!agentNo,
    retry: false,
  });
  const exitQ = useQuery({
    queryKey: ["agent-exit", exitNo],
    queryFn: () => api.getAgentExit(exitNo!),
    enabled: !!exitNo,
    retry: false,
  });
  // 只认属于本代理的清退单：URL 被手改成别人的单号时不能拿它来挡本代理的恢复
  const fromUrl = exitQ.data && exitQ.data.agentNo === agentNo ? exitQ.data : undefined;
  const exit = fromUrl ?? openExitQ.data ?? undefined;
  const exitOpen = !!exit && exit.status !== "CLOSED";
  const impact = useSuspendImpact(agent, !!agentNo);

  const invalidate = () => {
    for (const k of [["agents"], ["agent-options"], ["agent-impact-wo"], ["agent-impact-wd"], ["agent-impact-assets"], ["agent-assign"]]) {
      qc.invalidateQueries({ queryKey: k });
    }
  };
  // 状态迁移走整条保存：后端 save 是全量覆盖（name / contact / 比例都会按入参写），只传 status 会把档案抹空
  const setStatus = useMutation({
    mutationFn: (v: { agent: Agent; status: AgentStatus }) => api.saveAgent({ ...v.agent, status: v.status }),
    onSuccess: (a) => {
      notify.success(a.status === "SUSPENDED" ? `已停用 ${a.agentNo}：名下未完结工单正在改派平台运维，提现已冻结` : `已恢复 ${a.agentNo}`);
      invalidate();
    },
  });
  const startExit = useMutation({
    mutationFn: (v: { agentNo: string; reason: string }) => api.startAgentExit(v.agentNo, v.reason),
    onSuccess: (e) => {
      notify.success(`已发起清退 ${e.exitNo}，代理已停用`);
      setExitForm(null);
      invalidate();
      onOpenExit(e.exitNo);
    },
  });

  async function submitExit() {
    if (!agent || !exitForm) return;
    if (!exitForm.reason.trim()) { notify.error("请填写清退原因——它会写进停用记录，事后追溯靠它"); return; }
    const ok = await confirm({
      title: `发起清退 ${agent.agentNo}`,
      desc: "发起即停用：提现冻结（结清步除外）、名下未完结工单改派平台、不能再划拨。"
        + "之后须依次完成收回资产、结清、关闭账号三步，最后一步不可恢复。请输入代理编号确认。",
      danger: true,
      confirmText: "发起清退",
      requireText: agent.agentNo,
    });
    if (ok) startExit.mutate({ agentNo: agent.agentNo, reason: exitForm.reason.trim() });
  }

  const archived = !!agent?.archivedAt;
  const suspendDesc = `停用后：${impact.woText} 张未完结工单将改派平台运维；${impact.wdText} 笔在途提现冻结`
    + "（申请、审批通过、确认打款都会被拒，驳回仍可做；分润照常计提，恢复后继续）；不能再向其划拨设备或站点。";

  return (
    <Drawer
      open={!!agentNo}
      onOpenChange={(o) => !o && onClose()}
      title="代理商详情"
      desc="档案 · 停用影响 · 运维考核 · 清退"
      width="w-[720px]"
    >
      {agentQ.error ? (
        <ErrorState error={agentQ.error} onRetry={() => agentQ.refetch()} />
      ) : agentQ.isLoading ? (
        <div className="txt-caption text-muted-foreground">加载中…</div>
      ) : !agent ? (
        <Notice>找不到代理商 {agentNo} —— 编号可能输错了，或它不在你的数据范围内。</Notice>
      ) : (
        <div className="space-y-5">
          <DetailHeader
            no={agent.agentNo}
            title={agent.name}
            badge={<>
              <StatusBadge map={AGENT_STATUS} value={agent.status} />
              {archived && <StatusBadge map={ARCHIVED} value="ARCHIVED" />}
              {exit && <StatusBadge map={AGENT_EXIT_STATUS} value={exit.status} />}
            </>}
            meta={`${AGENT_TYPE_LABEL[agent.agentType ?? "AGENT"]} · ${agent.regionScope || "未设辖域"} · ${agent.contact || "无联系方式"}`}
            actions={
              <StateActions actions={[
                {
                  key: "exit-progress", label: "清退进度", primary: true,
                  when: !!exit, onRun: () => onOpenExit(exit!.exitNo),
                },
                {
                  key: "suspend", label: "停用", danger: true, perm: "agent:agent:update",
                  when: agent.status === "ENABLED" && !archived,
                  confirm: { title: `停用代理 ${agent.agentNo}`, desc: suspendDesc, confirmText: "确认停用" },
                  onRun: () => setStatus.mutateAsync({ agent, status: "SUSPENDED" }),
                },
                {
                  key: "resume", label: "恢复启用", perm: "agent:agent:update",
                  when: agent.status === "SUSPENDED" && !archived,
                  blockedReason: exitOpen ? `清退 ${exit!.exitNo} 进行中，清退是单向的，不能恢复` : null,
                  confirm: { title: `恢复代理 ${agent.agentNo}`, desc: "恢复后可再派单、可划拨；停用期间挂起的提现单继续走审批与打款。", confirmText: "恢复启用" },
                  onRun: () => setStatus.mutateAsync({ agent, status: "ENABLED" }),
                },
                {
                  key: "edit", label: "编辑档案", perm: "agent:agent:update",
                  when: !archived, onRun: () => onEdit(agent),
                },
                {
                  key: "start-exit", label: "发起清退", danger: true, perm: "agent:agent:update",
                  when: !archived && !exitOpen,
                  onRun: () => setExitForm({ reason: "" }),
                },
              ]} />
            }
          />

          {agent.status === "SUSPENDED" && !archived && (
            <Notice className="mb-0">
              停用中：不派新单、不能划拨、提现冻结（清退「结清」步除外）；分润照常计提，钱没丢，只是暂时拿不走。
            </Notice>
          )}

          <Tabs
            tabs={[{ key: "overview", label: "概况" }, { key: "assessments", label: "运维考核" }]}
            value={tab}
            onChange={(k) => setTab(k as "overview" | "assessments")}
          />

          {tab === "overview" && (
            <div className="space-y-5">
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <SummaryCard label="未完结工单" value={impact.woText} sub="停用时改派平台运维" />
                <SummaryCard label="在途提现" value={impact.wdText} sub="停用期间冻结" />
                <SummaryCard label="名下机柜" value={impact.cabText} sub="清退第一步须收回" />
                <SummaryCard label="名下站点" value={impact.siteText} sub="清退第一步须收回" />
              </div>
              <div className="grid grid-cols-2 gap-x-6">
                <Field label="登记类型">{AGENT_TYPE_LABEL[agent.agentType ?? "AGENT"]}</Field>
                <Field label="默认分润比例"><span className="tabular-nums">{(agent.shareRate * 100).toFixed(0)}%</span></Field>
                <Field label="辖域">{agent.regionScope || <span className="text-muted-foreground">未设</span>}</Field>
                <Field label="联系方式">{agent.contact || <span className="text-muted-foreground">未填</span>}</Field>
              </div>

              {/* 后端没有「按代理查清退单」的端点：从别处进来（没带 ?exit=）时，只能凭单号打开进度 */}
              {!exit && agent.status === "SUSPENDED" && allow("agent:agent:read") && (
                <section className="space-y-2">
                  <div className="txt-strong">已有清退单？</div>
                  <p className="txt-caption text-muted-foreground">
                    发起清退后进度页的链接里带着清退单号（AX 开头）。从列表进来看不到它时，输入单号打开进度。
                  </p>
                  <div className="flex gap-2">
                    <Input value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="AX 开头的清退单号" className="max-w-[220px]" />
                    <Button variant="outline" disabled={!lookup.trim()} onClick={() => onOpenExit(lookup.trim())}>查看进度</Button>
                  </div>
                  {exitNo && exitQ.error ? <p className="txt-caption text-destructive-ink">清退单 {exitNo} 查不到，核对单号后重试</p> : null}
                </section>
              )}
            </div>
          )}

          {tab === "assessments" && <AgentOpsAssessments agentNo={agent.agentNo} />}
        </div>
      )}

      {/* 发起清退：原因必填，提交时再用 requireText 二次确认 —— 发起即停用，影响立刻生效 */}
      <Drawer
        open={!!exitForm}
        onOpenChange={(o) => !o && setExitForm(null)}
        title={`发起清退 · ${agent?.name ?? ""}（${agentNo ?? ""}）`}
        desc="收回资产 → 结清 → 关闭账号，每步门禁全过才能推进"
        width="w-[520px]"
        footer={
          <>
            <Button variant="outline" onClick={() => setExitForm(null)}>取消</Button>
            <Button variant="destructive" disabled={startExit.isPending} onClick={submitExit}>发起清退</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Notice className="mb-0">
            发起即停用：{impact.woText} 张未完结工单改派平台运维，{impact.wdText} 笔在途提现冻结（到「结清」步才放开），不能再划拨。
          </Notice>
          <div className="space-y-1">
            <label className="txt-caption text-muted-foreground" htmlFor="exit-reason">清退原因（必填，写进停用记录）</label>
            <Textarea
              id="exit-reason"
              value={exitForm?.reason ?? ""}
              onChange={(v) => setExitForm({ reason: v })}
              placeholder="如：合作到期不续约 / 连续三个月运维考核低档 / 违规"
            />
          </div>
        </div>
      </Drawer>
      {dialog}
    </Drawer>
  );
}
