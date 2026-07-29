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
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
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
  // 「显示已归档」开关（TDD §10.1：列表默认过滤已归档）。切 tab 复位。
  const [showArchived, setShowArchived] = useState(false);
  const { confirm, dialog } = useConfirm();
  useEffect(() => { if (qTab && TABS.some((x) => x.key === qTab)) { setTab(qTab); setPage(1); setShowArchived(false); } }, [qTab]);

  // 转工单：告警→工单闭环（我们比竞品多的一环，竞品到通知就断了）
  const canRaise = allow("workorder:wo:create");
  const canConfig = allow("workorder:alarm:config");

  const [codeForm, setCodeForm] = useState<Partial<AlarmCode> | null>(null);
  const [ruleForm, setRuleForm] = useState<Partial<AlarmRule> | null>(null);

  const q = useQuery<PageResult<AlarmRecord | AlarmNotice | AlarmCode | AlarmRule>>({
    // showArchived 必须进 queryKey，否则切开关不重新拉数据
    queryKey: ["alarm", tab, page, keyword, level, status, showArchived],
    queryFn: () =>
      tab === "notices" ? api.listAlarmNotices({ page, size: SIZE, keyword })
      : tab === "codes" ? api.listAlarmCodes({ page, size: SIZE, keyword, showArchived })
      : tab === "rules" ? api.listAlarmRules({ page, size: SIZE, keyword, showArchived })
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
  // 归档 / 恢复：错误由全局 MutationCache 接管，这里只管成功后的失效与提示。
  const archiveCodeM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveAlarmCode(v.no) : api.archiveAlarmCode(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  const archiveRuleM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveAlarmRule(v.no) : api.archiveAlarmRule(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  // 告警代码 / 通知规则非主数据，不要求手输编号确认（requireText 只留给机柜/站点/角色等主数据）。
  const askArchiveCode = async (c: AlarmCode) => {
    if (await confirm(archiveConfirm("告警代码", c.code))) archiveCodeM.mutate({ no: c.code, undo: false });
  };
  const askUnarchiveCode = async (c: AlarmCode) => {
    if (await confirm(unarchiveConfirm("告警代码", c.code))) archiveCodeM.mutate({ no: c.code, undo: true });
  };
  const askArchiveRule = async (r: AlarmRule) => {
    if (await confirm(archiveConfirm("通知规则", r.ruleNo))) archiveRuleM.mutate({ no: r.ruleNo, undo: false });
  };
  const askUnarchiveRule = async (r: AlarmRule) => {
    if (await confirm(unarchiveConfirm("通知规则", r.ruleNo))) archiveRuleM.mutate({ no: r.ruleNo, undo: true });
  };
  // 归档时间列只在「显示已归档」打开时插入（默认视图里整列都是 "-"），且固定在操作列之前，保证操作列最右。
  const archivedCol = <T extends { archivedAt: string | null }>(): Column<T>[] =>
    showArchived ? [{ header: "归档时间", cell: (r: T) => <ArchivedAt at={r.archivedAt} /> }] : [];
  const archivedCsv = <T extends { archivedAt: string | null }>(): CsvColumn<T>[] =>
    showArchived ? [{ header: "归档时间", value: (r: T) => r.archivedAt ? fmtTime(r.archivedAt) : "-" }] : [];
  // 导出当页数据（§10.2），列与表格可见列一致。
  const onExportOf = <T,>(name: string, cols: CsvColumn<T>[]) =>
    () => exportCsv<T>(name, cols, (q.data?.list ?? []) as T[]);

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
    ...archivedCol<AlarmCode>(),
    {
      header: t("common.actions"),
      cell: (c) => (
        <ArchiveActions
          archived={!!c.archivedAt}
          canWrite={canConfig}
          onArchive={() => askArchiveCode(c)}
          onUnarchive={() => askUnarchiveCode(c)}
          actions={<Button size="sm" variant="outline" onClick={() => setCodeForm(c)}>{t("common.edit")}</Button>}
        />
      ),
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
    ...archivedCol<AlarmRule>(),
    {
      header: t("common.actions"),
      cell: (r) => (
        <ArchiveActions
          archived={!!r.archivedAt}
          canWrite={canConfig}
          onArchive={() => askArchiveRule(r)}
          onUnarchive={() => askUnarchiveRule(r)}
          actions={<Button size="sm" variant="outline" onClick={() => setRuleForm(r)}>{t("common.edit")}</Button>}
        />
      ),
    },
  ];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); setLevel(""); setStatus(""); setShowArchived(false); }} />

      {tab === "records" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索告警号 / 柜机 / 告警码 / 工单号"
          onExport={onExportOf<AlarmRecord>("告警记录", [
            { header: "告警号", value: (a) => a.alarmNo },
            { header: "柜机", value: (a) => a.cabinetNo },
            { header: "站点", value: (a) => a.siteName },
            { header: "厂商", value: (a) => a.vendorCode },
            { header: "告警码", value: (a) => a.alarmCode },
            { header: "厂商错误码", value: (a) => a.vendorErrorCode },
            { header: "等级", value: (a) => LEVEL[a.level].label },
            { header: "发生时间", value: (a) => fmtTime(a.occurredAt) },
            { header: "状态", value: (a) => REC_STATUS[a.status].label },
            { header: "关联工单", value: (a) => a.workOrderNo ?? "-" },
            { header: "备注", value: (a) => a.remark },
          ])}>
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
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索通知号 / 告警号 / 接收人"
          onExport={onExportOf<AlarmNotice>("告警通知", [
            { header: "通知号", value: (n) => n.noticeNo },
            { header: "告警号", value: (n) => n.alarmNo },
            { header: "渠道", value: (n) => CHANNEL_LABEL[n.channel] },
            { header: "接收人", value: (n) => n.target },
            { header: "发送时间", value: (n) => fmtTime(n.sentAt) },
            { header: "状态", value: (n) => (n.status === "SENT" ? "已发送" : "发送失败") },
            { header: "失败原因", value: (n) => n.failReason ?? "-" },
          ])} />
      )}
      {tab === "codes" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索代码 / 信息 / 建议处置"
          onAdd={canConfig ? () => setCodeForm({ level: "WARN", autoWorkOrder: false, message: "", suggestion: "" }) : undefined} addLabel="新增告警代码"
          onExport={onExportOf<AlarmCode>("告警代码", [
            { header: "告警代码", value: (c) => c.code },
            { header: "告警信息", value: (c) => c.message },
            { header: "等级", value: (c) => LEVEL[c.level].label },
            { header: "建议处置", value: (c) => c.suggestion },
            { header: "自动开工单", value: (c) => (c.autoWorkOrder ? "是" : "否") },
            ...archivedCsv<AlarmCode>(),
          ])}>
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {tab === "rules" && (
        <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder="搜索规则号 / 告警代码 / 通知目标"
          onAdd={canConfig ? () => setRuleForm({ channel: "SMS", method: "INSTANT", quietStart: "22:00", quietEnd: "08:00", escalateMinutes: 60, status: "ACTIVE", alarmCode: "", target: "" }) : undefined} addLabel="新增通知规则"
          onExport={onExportOf<AlarmRule>("通知规则", [
            { header: "规则号", value: (r) => r.ruleNo },
            { header: "告警代码", value: (r) => r.alarmCode },
            { header: "通知目标", value: (r) => r.target },
            { header: "渠道", value: (r) => CHANNEL_LABEL[r.channel] },
            { header: "方式", value: (r) => (r.method === "INSTANT" ? "即时" : "汇总") },
            { header: "静默窗口", value: (r) => (r.quietStart && r.quietEnd ? `${r.quietStart} - ${r.quietEnd}` : "不静默") },
            { header: "升级策略", value: (r) => (r.escalateMinutes > 0 ? `${r.escalateMinutes} 分钟未处理升级` : "不升级") },
            { header: "状态", value: (r) => (r.status === "ACTIVE" ? "启用" : "停用") },
            ...archivedCsv<AlarmRule>(),
          ])}>
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}
      {/* 权限降级显式提示（§3.2）：不静默隐藏操作列，否则会被当成功能坏了 */}
      {(tab === "codes" || tab === "rules") && !canConfig && (
        <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无告警配置权限（workorder:alarm:config），不能新增、编辑或归档</div>
      )}

      {tab === "records" && <DataTable rowKey={(a: AlarmRecord) => a.alarmNo} columns={recordCols} rows={q.data?.list as AlarmRecord[]} loading={q.isLoading} empty="暂无告警记录——设备运行正常，或当前筛选条件下无匹配，试着清空等级 / 状态筛选。" />}
      {tab === "notices" && <DataTable rowKey={(n: AlarmNotice) => n.noticeNo} columns={noticeCols} rows={q.data?.list as AlarmNotice[]} loading={q.isLoading} empty="暂无告警通知——通知由「通知规则」命中告警后自动产生，先去规则页确认规则已启用。" />}
      {tab === "codes" && <DataTable rowKey={(c: AlarmCode) => c.code} columns={codeCols} rows={q.data?.list as AlarmCode[]} loading={q.isLoading} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的告警代码——换个关键词，或点「新增告警代码」补一条。" : "暂无在用告警代码——可能都已归档（打开「显示已归档」查看），或点「新增告警代码」建第一条处置预案。"} />}
      {tab === "rules" && <DataTable rowKey={(r: AlarmRule) => r.ruleNo} columns={ruleCols} rows={q.data?.list as AlarmRule[]} loading={q.isLoading} rowClassName={archivedRowClass} empty={showArchived ? "没有匹配的通知规则——换个关键词，或点「新增通知规则」补一条。" : "暂无在用通知规则——可能都已归档（打开「显示已归档」查看），或点「新增通知规则」为关键告警配通知目标。"} />}
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

      {dialog}
    </div>
  );
}

export default function AlarmsPage() {
  return <Suspense fallback={null}><AlarmsInner /></Suspense>;
}
