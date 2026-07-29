"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import type { AlarmRecord, AlarmNotice, AlarmCode, AlarmRule, AlarmLevel, PageResult } from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "records", label: "告警记录" },
  { key: "notices", label: "告警通知" },
  { key: "codes", label: "告警代码" },
  { key: "rules", label: "通知规则" },
];

const LEVEL: Record<AlarmLevel, { label: string; tone: "muted" | "warning" | "danger" }> = {
  INFO: { label: "提示", tone: "muted" },
  WARN: { label: "警告", tone: "warning" },
  CRITICAL: { label: "严重", tone: "danger" },
};

const REC_STATUS: Record<AlarmRecord["status"], { label: string; tone: "warning" | "default" | "muted" }> = {
  OPEN: { label: "待处理", tone: "warning" },
  ACKED: { label: "已受理", tone: "default" },
  CLOSED: { label: "已关闭", tone: "muted" },
};

const CHANNEL_LABEL: Record<AlarmNotice["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "推送", WEBHOOK: "Webhook" };

const LEVEL_OPTIONS = [{ value: "INFO", label: "提示" }, { value: "WARN", label: "警告" }, { value: "CRITICAL", label: "严重" }];
const CHANNEL_OPTIONS = [{ value: "SMS", label: "短信" }, { value: "EMAIL", label: "邮件" }, { value: "PUSH", label: "推送" }, { value: "WEBHOOK", label: "Webhook" }];

const CODE_FIELDS: FieldDef[] = [
  { key: "code", label: "告警代码", readOnlyOnEdit: true, placeholder: "SLOT_STUCK" },
  { key: "message", label: "告警信息", placeholder: "卡槽卡宝" },
  { key: "level", label: "等级", type: "select", options: LEVEL_OPTIONS },
  { key: "suggestion", label: "建议处置", placeholder: "远程弹仓一次；仍失败则锁槽并派维修" },
  { key: "autoWorkOrder", label: "自动开工单", type: "switch" },
];

const RULE_FIELDS: FieldDef[] = [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "alarmCode", label: "告警代码", placeholder: "OFFLINE" },
  { key: "target", label: "通知目标", placeholder: "运维值班组" },
  { key: "channel", label: "渠道", type: "select", options: CHANNEL_OPTIONS },
  { key: "method", label: "方式", type: "select", options: [{ value: "INSTANT", label: "即时" }, { value: "DIGEST", label: "汇总" }] },
  { key: "quietStart", label: "静默窗口起", placeholder: "22:00（留空=不静默）" },
  { key: "quietEnd", label: "静默窗口止", placeholder: "08:00（留空=不静默）" },
  { key: "escalateMinutes", label: "升级策略（分钟未处理则升级，0=不升级）", type: "number" },
  { key: "status", label: "状态", type: "select", options: [{ value: "ACTIVE", label: "启用" }, { value: "INACTIVE", label: "停用" }] },
];

function AlarmsInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const { t } = useI18n();
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const [tab, setTab] = useState(TABS.some((x) => x.key === qTab) ? (qTab as string) : "records");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [level, setLevel] = useState("");
  const [status, setStatus] = useState("");
  useEffect(() => { if (qTab && TABS.some((x) => x.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  // 转工单：告警→工单闭环（我们比竞品多的一环，竞品到通知就断了）
  const canRaise = allow("workorder:wo:create");
  const canConfig = allow("workorder:alarm:config");

  const [codeForm, setCodeForm] = useState<Partial<AlarmCode> | null>(null);
  const [ruleForm, setRuleForm] = useState<Partial<AlarmRule> | null>(null);

  const q = useQuery<PageResult<AlarmRecord | AlarmNotice | AlarmCode | AlarmRule>>({
    queryKey: ["alarm", tab, page, keyword, level, status],
    queryFn: () =>
      tab === "notices" ? api.listAlarmNotices({ page, size: SIZE, keyword })
      : tab === "codes" ? api.listAlarmCodes({ page, size: SIZE, keyword })
      : tab === "rules" ? api.listAlarmRules({ page, size: SIZE, keyword })
      : api.listAlarmRecords({ page, size: SIZE, keyword, level: level || undefined, status: status || undefined }),
    placeholderData: keepPreviousData,
  });

  const onSaved = (setter: (v: null) => void) => () => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(t("common.success")); setter(null); };
  const saveCode = useMutation({ mutationFn: (v: Partial<AlarmCode>) => api.saveAlarmCode(v), onSuccess: onSaved(setCodeForm) });
  const saveRule = useMutation({ mutationFn: (v: Partial<AlarmRule>) => api.saveAlarmRule(v), onSuccess: onSaved(setRuleForm) });
  const raise = useMutation({
    mutationFn: (alarmNo: string) => api.raiseAlarmWorkOrder(alarmNo),
    onSuccess: (r) => { notify.success(`已转工单 ${r.workOrderNo}`); qc.invalidateQueries({ queryKey: ["alarm"] }); },
  });

  const recordCols: Column<AlarmRecord>[] = [
    { header: "告警号", cell: (a) => <span className="font-medium">{a.alarmNo}</span> },
    { header: "柜机 / 站点", cell: (a) => <>{a.cabinetNo} <span className="text-muted-foreground">· {a.siteName}</span></> },
    { header: "厂商", cell: (a) => <Badge tone="outline">{a.vendorCode}</Badge> },
    // 双列并存 = 多厂商错误码归一化：平台统一码用于规则/统计，厂商原始码用于对厂商排障
    { header: "告警码", cell: (a) => <span className="font-medium tabular-nums">{a.alarmCode}</span> },
    { header: "厂商错误码", cell: (a) => <span className="text-muted-foreground tabular-nums">{a.vendorErrorCode}</span> },
    { header: "等级", cell: (a) => <Badge tone={LEVEL[a.level].tone}>{LEVEL[a.level].label}</Badge> },
    { header: "发生时间", cell: (a) => <span className="text-muted-foreground">{fmtTime(a.occurredAt)}</span> },
    { header: "状态", cell: (a) => <Badge tone={REC_STATUS[a.status].tone}>{REC_STATUS[a.status].label}</Badge> },
    { header: "关联工单", cell: (a) => a.workOrderNo ? <span className="tabular-nums">{a.workOrderNo}</span> : <span className="text-muted-foreground">-</span> },
    { header: "备注", cell: (a) => <span className="text-muted-foreground">{a.remark}</span> },
    {
      header: t("common.actions"),
      cell: (a) => canRaise && !a.workOrderNo
        ? <Button size="sm" variant="outline" disabled={raise.isPending} onClick={() => raise.mutate(a.alarmNo)}>转工单</Button>
        : <span className="text-muted-foreground">-</span>,
    },
  ];

  const noticeCols: Column<AlarmNotice>[] = [
    { header: "通知号", cell: (n) => <span className="font-medium">{n.noticeNo}</span> },
    { header: "告警号", cell: (n) => <span className="text-muted-foreground">{n.alarmNo}</span> },
    { header: "渠道", cell: (n) => <Badge tone="outline">{CHANNEL_LABEL[n.channel]}</Badge> },
    { header: "接收人", cell: (n) => n.target },
    { header: "发送时间", cell: (n) => <span className="text-muted-foreground">{fmtTime(n.sentAt)}</span> },
    { header: "状态", cell: (n) => <Badge tone={n.status === "SENT" ? "success" : "danger"}>{n.status === "SENT" ? "已发送" : "发送失败"}</Badge> },
    { header: "失败原因", cell: (n) => <span className="text-muted-foreground">{n.failReason ?? "-"}</span> },
  ];

  const codeCols: Column<AlarmCode>[] = [
    { header: "告警代码", cell: (c) => <span className="font-medium tabular-nums">{c.code}</span> },
    { header: "告警信息", cell: (c) => c.message },
    { header: "等级", cell: (c) => <Badge tone={LEVEL[c.level].tone}>{LEVEL[c.level].label}</Badge> },
    // 建议处置 + 自动开工单：字典即处置预案（比竞品多的两列）
    { header: "建议处置", cell: (c) => <span className="text-muted-foreground">{c.suggestion}</span> },
    { header: "自动开工单", cell: (c) => c.autoWorkOrder ? <Badge tone="success">是</Badge> : <Badge tone="muted">否</Badge> },
    {
      header: t("common.actions"),
      cell: (c) => canConfig ? <Button size="sm" variant="outline" onClick={() => setCodeForm(c)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span>,
    },
  ];

  const ruleCols: Column<AlarmRule>[] = [
    { header: "规则号", cell: (r) => <span className="font-medium">{r.ruleNo}</span> },
    { header: "告警代码", cell: (r) => <span className="tabular-nums">{r.alarmCode}</span> },
    { header: "通知目标", cell: (r) => r.target },
    { header: "渠道", cell: (r) => <Badge tone="outline">{CHANNEL_LABEL[r.channel]}</Badge> },
    { header: "方式", cell: (r) => r.method === "INSTANT" ? "即时" : "汇总" },
    // 静默窗口 / 升级策略：防夜间轰炸与告警风暴（比竞品多）
    { header: "静默窗口", cell: (r) => r.quietStart && r.quietEnd ? <span className="tabular-nums">{r.quietStart} - {r.quietEnd}</span> : <span className="text-muted-foreground">不静默</span> },
    { header: "升级策略", cell: (r) => r.escalateMinutes > 0 ? <span className="tabular-nums">{r.escalateMinutes} 分钟未处理升级</span> : <span className="text-muted-foreground">不升级</span> },
    { header: "状态", cell: (r) => r.status === "ACTIVE" ? <Badge tone="success">启用</Badge> : <Badge tone="muted">停用</Badge> },
    {
      header: t("common.actions"),
      cell: (r) => canConfig ? <Button size="sm" variant="outline" onClick={() => setRuleForm(r)}>{t("common.edit")}</Button> : <span className="text-muted-foreground">-</span>,
    },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setLevel(""); setStatus(""); }} />

      {tab === "records" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索告警号 / 柜机 / 告警码 / 工单号">
          <Select value={level} onChange={(e) => { setLevel(e.target.value); setPage(1); }}>
            <option value="">全部等级</option>
            <option value="INFO">提示</option>
            <option value="WARN">警告</option>
            <option value="CRITICAL">严重</option>
          </Select>
          <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
            <option value="">全部状态</option>
            <option value="OPEN">待处理</option>
            <option value="ACKED">已受理</option>
            <option value="CLOSED">已关闭</option>
          </Select>
        </Toolbar>
      )}
      {tab === "notices" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索通知号 / 告警号 / 接收人" />
      )}
      {tab === "codes" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代码 / 信息 / 建议处置"
          onAdd={canConfig ? () => setCodeForm({ level: "WARN", autoWorkOrder: false, message: "", suggestion: "" }) : undefined} addLabel="新增告警代码" />
      )}
      {tab === "rules" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索规则号 / 告警代码 / 通知目标"
          onAdd={canConfig ? () => setRuleForm({ channel: "SMS", method: "INSTANT", quietStart: "22:00", quietEnd: "08:00", escalateMinutes: 60, status: "ACTIVE", alarmCode: "", target: "" }) : undefined} addLabel="新增通知规则" />
      )}

      {tab === "records" && <DataTable rowKey={(a: AlarmRecord) => a.alarmNo} columns={recordCols} rows={q.data?.list as AlarmRecord[]} loading={q.isLoading} />}
      {tab === "notices" && <DataTable rowKey={(n: AlarmNotice) => n.noticeNo} columns={noticeCols} rows={q.data?.list as AlarmNotice[]} loading={q.isLoading} />}
      {tab === "codes" && <DataTable rowKey={(c: AlarmCode) => c.code} columns={codeCols} rows={q.data?.list as AlarmCode[]} loading={q.isLoading} />}
      {tab === "rules" && <DataTable rowKey={(r: AlarmRule) => r.ruleNo} columns={ruleCols} rows={q.data?.list as AlarmRule[]} loading={q.isLoading} />}
      {q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

      {/* 告警代码 编辑抽屉 */}
      <FormDrawer open={!!codeForm} onOpenChange={(o) => !o && setCodeForm(null)}
        titleNew="新增告警代码" titleEdit={`编辑告警代码 ${codeForm?.code ?? ""}`} isEdit={!!codeForm?.code}
        fields={CODE_FIELDS} value={(codeForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCodeForm(v as Partial<AlarmCode>)}
        onSubmit={() => codeForm && saveCode.mutate(codeForm)} submitting={saveCode.isPending} />

      {/* 通知规则 编辑抽屉 */}
      <FormDrawer open={!!ruleForm} onOpenChange={(o) => !o && setRuleForm(null)}
        titleNew="新增通知规则" titleEdit={`编辑通知规则 ${ruleForm?.ruleNo ?? ""}`} isEdit={!!ruleForm?.ruleNo}
        fields={RULE_FIELDS} value={(ruleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRuleForm(v as Partial<AlarmRule>)}
        onSubmit={() => ruleForm && saveRule.mutate(ruleForm)} submitting={saveRule.isPending} />
    </div>
  );
}

export default function AlarmsPage() {
  return <Suspense fallback={null}><AlarmsInner /></Suspense>;
}
