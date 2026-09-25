"use client";

// 告警详情抽屉（方案 §8.1「告警详情抽屉」）：
//   DetailHeader（业务名称 · 状态 · 等级 · 处置优先级 · 动作）
//   影响评估一行
//   页签：处置预案 │ 证据 │ 时间线 │ 处置 │ 历史
//
// 动作都经 StateActions：合法性按 ALARM_TRANSITIONS 算，缺权限渲染禁用并写明缺哪个码，
// 有副作用的全部先确认（R4）。「处置」的确认文案直接用处置预览拼出来 ——
// 运营点下去之前就知道会开什么单、派给谁、是不是并进已有的单。
import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Drawer, Field } from "@/components/ui/drawer";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs } from "@/components/ui/tabs";
import { Timeline } from "@/components/ui/timeline";
import { Notice } from "@/components/ui/notice";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { StateActions, type ActionSpec } from "@/components/state-actions";
import { RefLink } from "@/components/ref-link";
import { fmtTime } from "@/lib/utils";
import { notify } from "@/lib/notify";
import { canAlarmAction } from "@/lib/types";
import type {
  AlarmDetail, AlarmEvidence, AlarmRecord, DispositionPreview, AlarmCode, AlarmDisposition, WorkOrderPriority,
} from "@/lib/types";
import {
  ALARM_STATUS, ALARM_LEVEL, ALARM_PRIORITY, ALARM_DOMAIN, ALARM_CAUSE, ALARM_DISPOSITION,
  ALARM_SUBJECT, ALARM_CLOSE_REASON, ALARM_LOG_EVENT, IMPACT_SCOPE, IMPACT_PERIOD, SUBJECT_REF,
  subjectRefNo, roleLabel, durationText,
} from "./alarm-maps";
import { AlarmCloseDrawer, closeRestriction } from "./alarm-close-drawer";

const WO_TYPE: Record<string, string> = {
  FAULT: "故障维修", REFILL: "补货 / 取宝", INSPECT: "巡检", INSTALL: "安装", REMOVE: "撤机", COMPLAINT: "投诉", CLEAN: "清洁",
};

/** 受影响对象：按主体类型给 RefLink；没有详情页的主体（充电宝 / 支付…）显示纯文本。 */
export function AlarmSubject({ a }: { a: AlarmRecord }) {
  const b = a.business;
  if (!b?.subjectType) return <RefLink kind="cabinet" no={a.cabinetNo} />;
  const kind = SUBJECT_REF[b.subjectType];
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      <span className="text-muted-foreground">{ALARM_SUBJECT[b.subjectType]}</span>
      {kind
        ? <RefLink kind={kind} no={subjectRefNo(b.subjectType, b.subjectNo)} label={b.subjectNo ?? undefined} />
        : <span className="tabular-nums">{b.subjectNo ?? "-"}</span>}
    </span>
  );
}

/** 影响评估标签：「整站 · 高峰 · A 级 · 3 人在途」。 */
export function impactText(a: AlarmRecord): string {
  const b = a.business;
  if (!b) return "-";
  return [
    b.impactScope && IMPACT_SCOPE[b.impactScope],
    b.impactPeriod && IMPACT_PERIOD[b.impactPeriod],
    b.siteTier && `${b.siteTier} 级站`,
    b.inFlightOrders ? `${b.inFlightOrders} 人在途` : null,
  ].filter(Boolean).join(" · ") || "-";
}

/** 处置单号 → 链接：工单能跳；待办、客服单没有独立详情页，显示号并说明去哪看。 */
export function DispositionRef({ type, refNo }: { type: AlarmDisposition | null; refNo: string | null }) {
  if (!refNo) return <span className="text-muted-foreground">-</span>;
  if (type === "WORK_ORDER") return <RefLink kind="wo" no={refNo} />;
  return <span className="tabular-nums" title={type === "TODO" ? "在工作台 › 待办中心办理" : undefined}>{refNo}</span>;
}

/** 处置预览 → 一句人话。确认框与「处置」页签共用，两处说的必须是同一句。 */
export function previewSentences(p: DispositionPreview): string[] {
  const out: string[] = [];
  switch (p.type) {
    case "WORK_ORDER":
      out.push(p.mergeIntoWoNo
        ? `并入已有工单 ${p.mergeIntoWoNo}（不新开单）`
        : `新开一张「${WO_TYPE[p.woType ?? ""] ?? p.woType ?? "维修"}」工单，优先级 ${p.priority ? ALARM_PRIORITY[p.priority]?.label ?? p.priority : "-"}`);
      out.push(p.assigneeNo
        ? `派给 ${p.assigneeType === "AGENT" ? "代理" : "员工"} ${p.assigneeNo}（站点运维责任人）`
        : "按站点派单规则派给运维责任人；站点没有责任人时进入待派");
      break;
    case "TODO":
      out.push(`生成一条待办，由「${roleLabel(p.todoRole)}」岗位承接（在工作台 › 待办中心办理，办结即关闭本告警）`);
      break;
    case "CS_CASE":
      out.push("转一张客服单，由客服主动联系用户");
      break;
    case "AUTO_FIX":
      out.push("系统尝试自愈（撤单 / 按入柜时刻结单）；成功即以「系统自愈」关闭本告警");
      break;
    case "NOTIFY":
      out.push("仅通知相关人员，不产生任何人的工作");
      break;
    default:
      out.push("该告警码没有可用的处置配置");
  }
  if (p.fallback) out.push(`主路径不可用时改为：${ALARM_DISPOSITION[p.fallback]?.label ?? p.fallback}`);
  return out;
}

function parseEvidence(raw: string | null): { rows: AlarmEvidence[] | null; raw: string | null } {
  if (!raw) return { rows: [], raw: null };
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? { rows: v as AlarmEvidence[], raw } : { rows: null, raw };
  } catch {
    return { rows: null, raw };
  }
}

type TabKey = "plan" | "evidence" | "timeline" | "disposition" | "history";

export function AlarmDetailDrawer({
  alarmNo, codes, onClose, onOpenAlarm, initialTab,
}: {
  alarmNo: string | null;
  /** 告警码字典（业务名称 + 业务配置），由页面一次取好传入。 */
  codes: Map<string, AlarmCode>;
  onClose: () => void;
  /** 在「历史」里点另一条告警：换成那条（页面据此改 ?no=）。 */
  onOpenAlarm: (alarmNo: string) => void;
  initialTab?: TabKey;
}) {
  const qc = useQueryClient();
  const [tab, setTab] = React.useState<TabKey>(initialTab ?? "plan");
  const [closing, setClosing] = React.useState(false);
  React.useEffect(() => { setTab(initialTab ?? "plan"); }, [alarmNo, initialTab]);

  const q = useQuery({
    queryKey: ["alarm", "detail", alarmNo],
    queryFn: () => api.getAlarmDetail(alarmNo!),
    enabled: !!alarmNo,
  });
  const d: AlarmDetail | undefined = q.data;
  const a = d?.record;
  const disposed = !!(a?.business?.dispositionType || (!a?.business && a?.workOrderNo));
  const open = !!a && a.status !== "CLOSED";
  const pv = useQuery({
    queryKey: ["alarm", "preview", alarmNo],
    queryFn: () => api.alarmDispositionPreview(alarmNo!),
    // 已处置 / 已关闭的告警不再预览：结果已经在「处置」页签里了
    enabled: !!alarmNo && open && !disposed,
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["alarm"] });
    qc.invalidateQueries({ queryKey: ["alarmTodos"] });
  };
  const ack = useMutation({
    mutationFn: (no: string) => api.ackAlarm(no),
    onSuccess: (r) => { notify.success(`告警 ${r.alarmNo} 已受理`); refresh(); },
  });
  const dispose = useMutation({
    mutationFn: (no: string) => api.disposeAlarm(no),
    onSuccess: (r) => {
      notify.success(r.dispositionRef ? `已处置 · ${r.dispositionRef}` : "已处置（自愈 / 仅通知，没有产生单据）");
      refresh();
      setTab("disposition");
    },
  });

  const code = a ? codes.get(a.alarmCode) : undefined;
  const title = d?.codeName ?? code?.message ?? a?.alarmCode ?? "";
  const restriction = a ? closeRestriction(a, code) : null;

  const actions: ActionSpec[] = a ? [
    {
      key: "dispose", label: "立即处置", primary: true, perm: "workorder:wo:create",
      when: open && !disposed,
      blockedReason: pv.isLoading ? "正在计算处置方案…" : pv.error ? "处置预览失败，刷新后再试" : null,
      confirm: {
        title: `处置告警 ${a.alarmNo}`,
        desc: pv.data ? `将要发生：${previewSentences(pv.data).join("；")}。` : undefined,
        confirmText: "确认处置",
      },
      onRun: () => dispose.mutateAsync(a.alarmNo),
    },
    {
      key: "ack", label: "受理", perm: "workorder:alarm:ack",
      when: canAlarmAction(a.status, "ack"),
      confirm: {
        title: `受理告警 ${a.alarmNo}`,
        desc: "受理表示已认领处置责任，告警转为「已受理」，不开单也不关闭；要派人去现场请用「立即处置」。",
        confirmText: "确认受理",
      },
      onRun: () => ack.mutateAsync(a.alarmNo),
    },
    {
      key: "close", label: "关闭", perm: "workorder:alarm:close", danger: true,
      when: canAlarmAction(a.status, "close"),
      // 关闭抽屉本身就是确认（必选原因 + 说明），不再叠一层确认框
      onRun: () => setClosing(true),
    },
  ] : [];

  const tabs: { key: TabKey; label: string }[] = [
    { key: "plan", label: "处置预案" },
    { key: "evidence", label: "证据" },
    { key: "timeline", label: `时间线${d ? ` ${d.timeline.length}` : ""}` },
    { key: "disposition", label: "处置" },
    { key: "history", label: `历史${d ? ` ${d.recentSameSubject.length}` : ""}` },
  ];

  return (
    <>
      <Drawer
        open={!!alarmNo}
        onOpenChange={(o) => !o && onClose()}
        title={a ? `告警 ${a.alarmNo}` : "告警详情"}
        width="w-[min(100vw,640px)]"
      >
        {q.isLoading && <Skeleton className="h-40" />}
        {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
        {a && d && (
          <div className="space-y-4">
            <DetailHeader
              no={a.alarmNo}
              title={title}
              badge={
                <>
                  <StatusBadge map={ALARM_STATUS} value={a.status} />
                  <StatusBadge map={ALARM_LEVEL} value={a.level} className="whitespace-nowrap" />
                  {a.business?.priority && <StatusBadge map={ALARM_PRIORITY} value={a.business.priority} />}
                  {a.business?.domain && <StatusBadge map={ALARM_DOMAIN} value={a.business.domain} />}
                </>
              }
              meta={
                <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
                  <AlarmSubject a={a} />
                  {a.siteNo && a.business?.subjectType !== "SITE" && (
                    <span>· 站点 <RefLink kind="site" no={a.siteNo} /></span>
                  )}
                  <span>· 影响 {impactText(a)}</span>
                </span>
              }
              actions={<StateActions actions={actions} />}
            />

            {a.status === "CLOSED" && (
              <Notice>
                已于 {fmtTime(a.closedAt)} 关闭 · {a.closeReason ? ALARM_CLOSE_REASON[a.closeReason]?.label ?? a.closeReason : "-"}
                {a.closeNote ? ` · ${a.closeNote}` : ""}（{a.closedBy ?? "-"}）
              </Notice>
            )}
            {a.business?.parentAlarmNo && (
              <Notice>
                这条告警已被上层告警 <RefLink kind="alarm" no={a.business.parentAlarmNo} /> 取代：
                处置跟着上层走，这里只作证据保留。
              </Notice>
            )}

            <Tabs tabs={tabs} value={tab} onChange={(k) => setTab(k as TabKey)} />

            {tab === "plan" && <PlanTab a={a} d={d} code={code} />}
            {tab === "evidence" && <EvidenceTab d={d} />}
            {tab === "timeline" && (
              <Timeline
                items={d.timeline.map((t, i) => ({
                  key: `${i}-${t.at}`,
                  badge: ALARM_LOG_EVENT[t.event] ?? { label: t.event, tone: "outline" as const },
                  meta: `${fmtTime(t.at)} · ${t.operator ?? "-"}`,
                  text: t.note ?? undefined,
                }))}
                empty="这条告警还没有时间线——存量设备告警（业务告警上线之前产生的）不写时间线，只有上面的状态与关闭信息。"
              />
            )}
            {tab === "disposition" && (
              <DispositionTab a={a} disposed={disposed} preview={pv.data} previewLoading={pv.isLoading} previewError={pv.error} />
            )}
            {tab === "history" && (
              d.recentSameSubject.length === 0
                ? <p className="txt-body text-muted-foreground">同一对象近 7 天没有别的告警——这不是反复发作的问题。</p>
                : (
                  <ul className="divide-y divide-border">
                    {d.recentSameSubject.map((r) => (
                      <li key={r.alarmNo} className="flex flex-wrap items-center gap-2 py-2">
                        <button type="button" className="txt-strong tabular-nums text-primary hover:underline" onClick={() => onOpenAlarm(r.alarmNo)}>
                          {r.alarmNo}
                        </button>
                        <span>{codes.get(r.alarmCode)?.message ?? r.alarmCode}</span>
                        <StatusBadge map={ALARM_STATUS} value={r.status} />
                        <span className="ms-auto txt-caption text-muted-foreground tabular-nums">{fmtTime(r.occurredAt)}</span>
                      </li>
                    ))}
                  </ul>
                )
            )}
          </div>
        )}
      </Drawer>

      {a && (
        <AlarmCloseDrawer
          alarm={closing ? a : null}
          restriction={restriction}
          onClose={() => setClosing(false)}
          onDone={() => { setClosing(false); refresh(); }}
        />
      )}
    </>
  );
}

function PlanTab({ a, d, code }: { a: AlarmRecord; d: AlarmDetail; code?: AlarmCode }) {
  const b = a.business;
  const bc = code?.business;
  const reasons = priorityReasons(a, code);
  return (
    <div>
      <Field label="处置预案">{d.suggestion ?? code?.suggestion ?? <span className="text-muted-foreground">该码没有写处置预案</span>}</Field>
      <div className="grid grid-cols-2 gap-x-4">
        <Field label="根因">{b?.cause ? <StatusBadge map={ALARM_CAUSE} value={b.cause} /> : "-"}</Field>
        <Field label="首选处置">{bc?.disposition ? ALARM_DISPOSITION[bc.disposition]?.label : "-"}</Field>
        <Field label="首次发生">{fmtTime(b?.firstOccurredAt ?? a.occurredAt)}</Field>
        <Field label="持续">{durationText(b?.firstOccurredAt ?? a.occurredAt, b?.recoveredAt ?? a.closedAt)}</Field>
        <Field label="开单时刻">
          {b?.dueAt ? fmtTime(b.dueAt) : "-"}
          {bc?.woDelayMinutes ? <span className="block txt-caption text-muted-foreground">开单延迟 {bc.woDelayMinutes} 分钟：延迟内自愈就不派人</span> : null}
        </Field>
        <Field label="重复次数">{a.count > 1 ? `×${a.count}` : "1 次"}</Field>
      </div>
      {b?.priority && (
        <Field label={`处置优先级 · ${ALARM_PRIORITY[b.priority]?.label ?? b.priority}`}>
          {reasons.length
            ? <ul className="list-disc space-y-0.5 ps-5">{reasons.map((r) => <li key={r}>{r}</li>)}</ul>
            : <span className="text-muted-foreground">按告警码的基准优先级</span>}
        </Field>
      )}
      {a.dedupKey && (
        <Field label="去重键">
          <span className="tabular-nums">{a.dedupKey}</span>
          <span className="block txt-caption text-muted-foreground">同一个键的重复上报只累加次数，不另起一条告警</span>
        </Field>
      )}
      {!b && (
        <Field label="厂商 / 原始码">{a.vendorCode ?? "-"} · <span className="tabular-nums">{a.vendorErrorCode ?? "-"}</span></Field>
      )}
    </div>
  );
}

const PRIORITY_ORDER: WorkOrderPriority[] = ["LOW", "MEDIUM", "HIGH", "URGENT"];

/**
 * 「为什么是这个优先级」：基准 + 影响加成逐条列出（方案 §8.1）。
 * 规则逐条抄自后端 ImpactAssessor —— 改那边的加成规则，这里要一起改，否则解释与结果对不上。
 * 算出来与实际不一致时（判定器按业务刻度强制给档，如合同到期 60 / 30 / 7 天），明说，不硬解释。
 */
function priorityReasons(a: AlarmRecord, code?: AlarmCode): string[] {
  const b = a.business;
  const bc = code?.business;
  if (!b || !bc?.basePriority) return [];
  const out = [`基准：${ALARM_PRIORITY[bc.basePriority]?.label ?? bc.basePriority}（告警码配置）`];
  if (!bc.impactAdjust) {
    out.push("该码不参与影响加成——安全类隐患不因为是凌晨就不急");
    return out;
  }
  let d = 0;
  if (b.impactScope === "SITE") { d++; out.push("整站受影响：+1"); }
  if (b.impactPeriod === "PEAK") { d++; out.push("高峰时段：+1"); }
  if (b.siteTier === "A") { d++; out.push("A 级站点：+1"); }
  if (b.siteTier === "C" && b.impactPeriod !== "PEAK") { d--; out.push("C 级站点且非高峰：−1"); }
  if (b.domain === "RETURNABILITY" && (b.inFlightOrders ?? 0) >= 1) { d++; out.push(`${b.inFlightOrders} 位用户在途等着还：+1`); }
  const i = Math.max(0, Math.min(3, PRIORITY_ORDER.indexOf(bc.basePriority) + d));
  if (b.priority && PRIORITY_ORDER[i] !== b.priority) {
    out.push("实际档位由判定器按业务刻度给定（如合同到期 60 / 30 / 7 天三档），不走上面的加成");
  }
  return out;
}

function EvidenceTab({ d }: { d: AlarmDetail }) {
  const ev = parseEvidence(d.evidence);
  if (ev.rows === null) {
    // 解析不了也不能吞掉：证据是排障依据，原文照出
    return <pre className="whitespace-pre-wrap break-all rounded-control bg-muted p-3 txt-caption">{ev.raw}</pre>;
  }
  if (ev.rows.length === 0) {
    return <p className="txt-body text-muted-foreground">没有记录证据——存量设备告警由设备直接上报，证据就是上面的厂商原始码。</p>;
  }
  return (
    <ul className="divide-y divide-border">
      {ev.rows.map((e, i) => (
        <li key={i} className="flex flex-wrap items-center gap-2 py-2">
          <StatusBadge map={ALARM_CAUSE} value={e.signal as never} />
          <RefLink kind="cabinet" no={e.cabinetNo} />
          {e.slot != null && <span className="tabular-nums text-muted-foreground">#{e.slot}</span>}
          {e.note && <span className="text-muted-foreground">{e.note}</span>}
          <span className="ms-auto txt-caption text-muted-foreground tabular-nums">{fmtTime(e.at)}</span>
        </li>
      ))}
    </ul>
  );
}

function DispositionTab({
  a, disposed, preview, previewLoading, previewError,
}: {
  a: AlarmRecord; disposed: boolean; preview?: DispositionPreview; previewLoading: boolean; previewError: unknown;
}) {
  const b = a.business;
  if (disposed) {
    const type = b?.dispositionType ?? "WORK_ORDER";
    return (
      <div>
        <div className="grid grid-cols-2 gap-x-4">
          <Field label="处置方式"><StatusBadge map={ALARM_DISPOSITION} value={type} /></Field>
          <Field label="单号"><DispositionRef type={type} refNo={b?.dispositionRef ?? a.workOrderNo} /></Field>
        </div>
        {a.status !== "CLOSED" && (
          <p className="txt-caption text-muted-foreground">
            已处置但还没关闭：{type === "WORK_ORDER" ? "工单完工并复核通过后告警自动关闭" : type === "TODO" ? "待办办结后告警自动关闭" : "处置完成且信号恢复后自动关闭"}。
          </p>
        )}
      </div>
    );
  }
  if (a.status === "CLOSED") {
    return <p className="txt-body text-muted-foreground">告警在处置之前就已关闭（自愈 / 误报 / 被取代），没有产生单据。</p>;
  }
  if (previewLoading) return <Skeleton className="h-20" />;
  if (previewError) return <ErrorState error={previewError} />;
  if (!preview) return null;
  return (
    <div className="space-y-2">
      <p className="txt-caption text-muted-foreground">
        还没处置{b?.dueAt ? `（按开单延迟应在 ${fmtTime(b.dueAt)} 自动处置）` : ""}。现在点「立即处置」将会：
      </p>
      <ul className="list-disc space-y-1 ps-5 txt-body">
        {previewSentences(preview).map((s) => <li key={s}>{s}</li>)}
      </ul>
    </div>
  );
}
