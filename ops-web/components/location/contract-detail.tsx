"use client";

// 合同详情抽屉：条款 · 流程 · 留痕时间线。
//
// 为什么单独成件：合同走审批之后，「这份合同现在在谁手上、为什么卡着」
// 是最常被问的一件事，而它需要同时看 status + auditStage + termination 三处。
// 塞回列表页会让那个文件再长 200 行，且这三者的关系只有读代码才看得出来。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Contract, ContractStatus, ContractLogItem, ContractTerminationStatus } from "@/lib/types";
import { Drawer, Field } from "@/components/ui/drawer";

import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Badge } from "@/components/ui/badge";
import { Timeline, type TimelineItem } from "@/components/ui/timeline";

/**
 * 合同六态。
 *
 * <p>色调按**要不要人动手**分：DRAFT/PENDING 是在途，SIGNED 已签批待生效，
 * ACTIVE 正常，EXPIRED/TERMINATED 是终态（muted，不再吸引注意力）。
 * 此前列表里是一个三元表达式（ACTIVE 绿 / 其余红），
 * 于是 DRAFT 和 TERMINATED 都显示成「过期」——两件完全不同的事看着一样。
 */
export const CONTRACT_STATUS: StatusMap<ContractStatus> = {
  DRAFT: { label: "草稿", tone: "outline" },
  PENDING: { label: "审批中", tone: "warning" },
  SIGNED: { label: "已签批", tone: "default" },
  ACTIVE: { label: "生效中", tone: "success" },
  EXPIRED: { label: "已到期", tone: "muted" },
  TERMINATED: { label: "已终止", tone: "muted" },
};

const SHARE_MODE: Record<string, string> = {
  SHARE: "纯分成", ENTRY_FEE: "进场费", GUARANTEE: "保底", FREE: "免费",
};
const SHARE_BASE: Record<string, string> = { NET: "净额", GROSS: "毛额" };
const SETTLE_PERIOD: Record<string, string> = { MONTH: "按月", QUARTER: "按季" };
const AUDIT_STAGE: Record<string, string> = { OPS: "运营审条款", FINANCE: "财务会签" };

/** 终止申请三态。走 StatusMap 而不是内联三元——与页面层的棘轮口径一致。 */
const TERMINATION_STATUS: StatusMap<ContractTerminationStatus> = {
  PENDING: { label: "待审批", tone: "warning" },
  APPROVED: { label: "已获批", tone: "danger" },
  REJECTED: { label: "已驳回", tone: "muted" },
};

/** 留痕事件 → 人读文案。缺映射时**原样显示事件名**，不吞掉。 */
const EVENT_LABEL: Record<string, string> = {
  SUBMIT: "提交审批", WITHDRAW: "撤回", AUDIT_APPROVE: "运营通过", AUDIT_REJECT: "运营驳回",
  COSIGN_APPROVE: "财务会签通过", COSIGN_REJECT: "财务会签驳回", SIGN: "登记签署件",
  TERMINATION_REQUEST: "申请终止", TERMINATION_APPROVE: "终止获批", TERMINATION_REJECT: "终止驳回",
  TERMINATED: "已终止", RENEW_FROM: "续签生成", SUPPLEMENT_FROM: "补充协议生成",
};

const toTimeline = (logs: ContractLogItem[]): TimelineItem[] =>
  logs.map((l, i) => ({
    key: `${l.at}-${i}`,
    badge: { label: EVENT_LABEL[l.event] ?? l.event, tone: "outline" },
    meta: <>{l.at?.replace("T", " ").slice(0, 19)} · {l.operator ?? "系统"}</>,
    change: l.fromStatus && l.toStatus && l.fromStatus !== l.toStatus
      ? <>{CONTRACT_STATUS[l.fromStatus]?.label ?? l.fromStatus} → {CONTRACT_STATUS[l.toStatus]?.label ?? l.toStatus}</>
      : undefined,
    text: l.note || undefined,
  }));

export function ContractDetailDrawer({
  contractNo, onOpenChange,
}: {
  contractNo: string | null;
  onOpenChange: (open: boolean) => void;
}) {
  const open = !!contractNo;
  const detail = useQuery<Contract>({
    queryKey: ["contract-detail", contractNo],
    queryFn: () => api.getContract(contractNo!),
    enabled: open,
  });
  const logs = useQuery<ContractLogItem[]>({
    queryKey: ["contract-logs", contractNo],
    queryFn: () => api.listContractLogs(contractNo!),
    enabled: open,
  });

  const c = detail.data;
  const t = c?.terms;
  const f = c?.flow;

  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      title={`合同 ${contractNo ?? ""}`}
      desc="条款与流程都只读：改条款要回到草稿（撤回后编辑），推进流程用列表上的动作按钮"
      width="w-[640px]"
    >
      {detail.isLoading && <span className="text-muted-foreground">加载中…</span>}
      {detail.error && <span className="text-[var(--danger)]">读取失败，请重试</span>}
      {c && (
        <>
          <Field label="状态">
            <StatusBadge map={CONTRACT_STATUS} value={c.status} />
            {/* 状态只说「在审批中」，是谁的活由 auditStage 说——不显示它就看不出卡在哪一关 */}
            {c.status === "PENDING" && f?.auditStage && (
              <span className="ms-2 text-muted-foreground">当前环节：{AUDIT_STAGE[f.auditStage] ?? f.auditStage}</span>
            )}
            {typeof c.remainingDays === "number" && c.status === "ACTIVE" && (
              <span className="ms-2 text-muted-foreground tabular-nums">
                {c.remainingDays >= 0 ? `距到期 ${c.remainingDays} 天` : `已过期 ${-c.remainingDays} 天`}
              </span>
            )}
          </Field>
          <Field label="场地方 / 站点">{c.venueName} · {c.siteName}</Field>
          {/* 只显示到天：合同期是日粒度的业务概念，带上时分秒会让人以为它精确到那一刻 */}
          <Field label="合同期">{c.startAt?.slice(0, 10)} ~ {c.endAt?.slice(0, 10)}</Field>

          {/* —— 终止申请：有就必须显眼，它决定这份合同还能活多久 —— */}
          {f?.termination && (
            <Field label="终止申请">
              <StatusBadge map={TERMINATION_STATUS} value={f.termination.status} />
              <span className="ms-2">{f.termination.reason}</span>
              {f.termination.effectiveAt && (
                <div className="mt-1 txt-caption text-muted-foreground">
                  生效日 {f.termination.effectiveAt} —— 审批期间合同照常生效，到这一天才终止
                </div>
              )}
            </Field>
          )}

          {t && (
            <>
              <Field label="分成模式">
                {t.shareMode ? (SHARE_MODE[t.shareMode] ?? t.shareMode) : "—"}
                {t.shareBase && <span className="ms-2 text-muted-foreground">按{SHARE_BASE[t.shareBase] ?? t.shareBase}计</span>}
              </Field>
              <Field label="分成比例 / 进场费">
                <span className="tabular-nums">{(c.shareRate * 100).toFixed(0)}%</span>
                <span className="ms-3 tabular-nums">{c.entryFee}</span>
                {t.currency && <span className="ms-1 text-muted-foreground">{t.currency}</span>}
              </Field>
              {t.guaranteeAmount != null && (
                <Field label="保底金额"><span className="tabular-nums">{t.guaranteeAmount}</span></Field>
              )}
              <Field label="结算周期">{t.settlePeriod ? (SETTLE_PERIOD[t.settlePeriod] ?? t.settlePeriod) : "—"}</Field>
              {t.deviceQuota != null && <Field label="设备配额"><span className="tabular-nums">{t.deviceQuota}</span></Field>}
              {t.exclusive != null && <Field label="独家">{t.exclusive ? "是" : "否"}</Field>}
              {t.placementNote && <Field label="点位约定">{t.placementNote}</Field>}
              {t.remark && <Field label="备注">{t.remark}</Field>}
            </>
          )}

          {f?.signedAt && <Field label="签署日">{f.signedAt.slice(0, 10)}</Field>}
          {f?.prevContractNo && <Field label="续签自">{f.prevContractNo}</Field>}
          {f?.parentContractNo && <Field label="主合同">{f.parentContractNo}</Field>}

          <Field label="扫描件">
            {c.attachments.length
              ? c.attachments.map((a) => <Badge key={a.attachNo} tone="outline" className="me-1">{a.fileName}</Badge>)
              : <span className="text-[var(--warning)]">缺签署件 —— 审批可以过，但对账时拿不出凭据</span>}
          </Field>

          <div className="mt-4">
            <div className="txt-body text-muted-foreground mb-2">流转记录</div>
            <Timeline
              items={toTimeline(logs.data ?? [])}
              loading={logs.isLoading}
              empty="还没有流转记录——这份合同建好后还没被提交过"
            />
          </div>
        </>
      )}
    </Drawer>
  );
}
