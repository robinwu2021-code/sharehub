"use client";

// 告警管理：告警记录（业务告警中心）/ 告警通知 / 告警代码 / 通知规则。
//
// 2026-09-25 批次 6b：告警记录与告警代码两个 tab 按「业务告警 v2」重做，拆进 components/alarm/*；
// 通知流水与通知规则仍在本页。告警详情抽屉挂在页面层、由 `?no=` 驱动 ——
// 全站的 RefLink(kind="alarm") 都指向 `/alarms?no=…`，在哪个 tab 都能打开。
import { Suspense, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter, usePathname, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab, keepWithinTab } from "@/lib/hooks/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { PagedTable } from "@/components/ui/paged-table";
import type { Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { EnabledBadge } from "@/components/status";
import { RefLink } from "@/components/ref-link";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { useI18n } from "@/lib/i18n";
import { notify } from "@/lib/notify";
import { exportCsv, type CsvColumn } from "@/lib/export-csv";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import {
  ShowArchivedToggle, archivedRowClass, ArchivedAt, ArchiveActions,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { AlarmRecordsTab } from "@/components/alarm/alarm-records";
import { AlarmCodesTab } from "@/components/alarm/alarm-codes";
import { AlarmDetailDrawer } from "@/components/alarm/alarm-detail-drawer";
import type { AlarmNotice, AlarmCode, AlarmRule } from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）
const TAB_KEYS = ["records", "notices", "codes", "rules"] as const;

/** 通知流水的发送结果。收成映射表后导出列与徽标共用同一份文案。 */
const NOTICE_STATUS: StatusMap<AlarmNotice["status"]> = {
  SENT: { label: "已发送", tone: "success" },
  FAILED: { label: "发送失败", tone: "danger" },
};

const CHANNEL_LABEL: Record<AlarmNotice["channel"], string> = { SMS: "短信", EMAIL: "邮件", PUSH: "推送", WEBHOOK: "Webhook" };
const CHANNEL_OPTIONS = [{ value: "SMS", label: "短信" }, { value: "EMAIL", label: "邮件" }, { value: "PUSH", label: "推送" }, { value: "WEBHOOK", label: "Webhook" }];

/**
 * 通知规则表单。告警代码改为从字典选（方案 §8.3 / C8）：自由文本时打错一个字母，
 * 规则就永远不会命中，而界面上看不出任何异常。
 *
 * @form POST /api/ops/alarms/rules
 * @form POST /api/ops/alarms/rules/{ruleNo}
 */
const ruleFields = (codeOptions: { value: string; label: string }[]): FieldDef[] => [
  { key: "ruleNo", label: "规则号", readOnlyOnEdit: true, placeholder: "留空自动生成" },
  { key: "alarmCode", label: "告警代码", type: "select", options: codeOptions },
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
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const onTabChange = () => { paging.reset(); setKeyword(""); setShowArchived(false); };
  const tabs = useNavTabs("/alarms", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, onTabChange);
  const { confirm, dialog } = useConfirm();

  const canConfig = allow("workorder:alarm:config");
  // 重发通知会**真的再发一条**短信/邮件（重复触达 + 重复计费），故与只读/配置分开发码
  const canResendNotice = allow("workorder:alarm:notice_resend");

  // 告警详情写进 URL（?no=），刷新与分享不丢；全站 RefLink(kind="alarm") 都落到这里
  const detailNo = sp.get("no");
  const detailTab = sp.get("dtab") === "disposition" ? "disposition" as const : undefined;
  const openDetail = (alarmNo: string, dtab?: "disposition") => {
    const q = new URLSearchParams(sp.toString());
    q.set("no", alarmNo);
    if (dtab) q.set("dtab", dtab); else q.delete("dtab");
    router.replace(`${pathname}?${q.toString()}`, { scroll: false });
  };
  const closeDetail = () => {
    const q = new URLSearchParams(sp.toString());
    q.delete("no"); q.delete("dtab");
    router.replace(q.size ? `${pathname}?${q.toString()}` : pathname, { scroll: false });
  };

  // 告警码字典：列表显示业务名称、详情显示预案与配置、规则表单的码选择器 —— 一份取数三处用
  const codesQ = useQuery({
    queryKey: ["alarm", "codes", "dict"],
    queryFn: () => api.listAlarmCodes({ page: 1, size: UNPAGED_SIZE }),
    staleTime: 60_000,
  });
  const codes = useMemo(() => new Map((codesQ.data?.list ?? []).map((c) => [c.code, c])), [codesQ.data]);
  const codeOptions = useMemo(
    () => (codesQ.data?.list ?? []).map((c: AlarmCode) => ({ value: c.code, label: `${c.code} · ${c.message}` })),
    [codesQ.data],
  );

  const [ruleForm, setRuleForm] = useState<Partial<AlarmRule> | null>(null);

  const noticesQ = useQuery({
    queryKey: ["alarm", "notices", paging.page, paging.size, keyword],
    queryFn: () => api.listAlarmNotices({ page: paging.page, size: paging.size, keyword }),
    enabled: tab === "notices",
    placeholderData: keepWithinTab(tab),
  });
  const rulesQ = useQuery({
    queryKey: ["alarm", "rules", paging.page, paging.size, keyword, showArchived],
    queryFn: () => api.listAlarmRules({ page: paging.page, size: paging.size, keyword, showArchived }),
    enabled: tab === "rules",
    placeholderData: keepWithinTab(tab),
  });

  const saveRule = useMutation({
    mutationFn: (v: Partial<AlarmRule>) => api.saveAlarmRule(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(t("common.success")); setRuleForm(null); },
  });
  // 重发通知（拍板 #6）：服务端新增一条流水、原记录不动，故必须 invalidate 才看得到补发那条。
  // 拒绝（键重复 / 已发送 / 命中拉黑 / 告警已关闭）由全局 MutationCache 弹错，这里不重复处理。
  const resendNotice = useMutation({
    mutationFn: (v: { noticeNo: string; idempotencyKey: string }) => api.resendAlarmNotice(v.noticeNo, { idempotencyKey: v.idempotencyKey }),
    onSuccess: (n) => {
      notify.success(`已重发 · 新通知号 ${n.noticeNo}（原记录 ${n.resendOf} 保留）`);
      qc.invalidateQueries({ queryKey: ["alarm"] });
    },
  });
  const archiveRuleM = useMutation({
    mutationFn: (v: { no: string; undo: boolean }) => v.undo ? api.unarchiveAlarmRule(v.no) : api.archiveAlarmRule(v.no),
    onSuccess: (_r, v) => { qc.invalidateQueries({ queryKey: ["alarm"] }); notify.success(v.undo ? "已恢复" : "已归档"); },
  });
  const askArchiveRule = async (r: AlarmRule) => {
    if (await confirm(archiveConfirm("通知规则", r.ruleNo))) archiveRuleM.mutate({ no: r.ruleNo, undo: false });
  };
  const askUnarchiveRule = async (r: AlarmRule) => {
    if (await confirm(unarchiveConfirm("通知规则", r.ruleNo))) archiveRuleM.mutate({ no: r.ruleNo, undo: true });
  };
  const archivedCsv = <T extends { archivedAt: string | null }>(): CsvColumn<T>[] =>
    showArchived ? [{ header: "归档时间", value: (r: T) => r.archivedAt ? fmtTime(r.archivedAt) : "-" }] : [];

  const noticeCols: Column<AlarmNotice>[] = [
    { header: "通知号", cell: (n) => <span className="txt-strong tabular-nums">{n.noticeNo}</span> },
    { header: "告警号", cell: (n) => <RefLink kind="alarm" no={n.alarmNo} /> },
    { header: "渠道", cell: (n) => <Badge tone="outline">{CHANNEL_LABEL[n.channel]}</Badge> },
    { header: "接收人", cell: (n) => n.target },
    { header: "发送时间", cell: (n) => <span className="text-muted-foreground">{fmtTime(n.sentAt)}</span> },
    { header: "状态", cell: (n) => <StatusBadge map={NOTICE_STATUS} value={n.status} /> },
    { header: "失败原因", cell: (n) => <span className="text-muted-foreground">{n.failReason ?? "-"}</span> },
    // 重发来源：让人一眼看出「这条是补发的」，否则同一目标两条成功流水像是系统发了两遍
    { header: "重发自", cell: (n) => n.resendOf ? <Badge tone="warning">{n.resendOf}</Badge> : <span className="text-muted-foreground">-</span> },
    {
      header: t("common.actions"),
      // 只有失败的才给重发：成功的再发一遍就是重复轰炸值班人 + 重复计费（拍板 #6）
      cell: (n) => !canResendNotice || n.status !== "FAILED" ? <span className="text-muted-foreground">-</span> : (
        <Button size="sm" variant="outline" disabled={resendNotice.isPending}
          onClick={async () => {
            const ok = await confirm({
              title: `重发通知 ${n.noticeNo}`,
              desc: `将按原渠道（${CHANNEL_LABEL[n.channel]}）与原目标 ${n.target} 再发一次。重发会新增一条流水，原记录保留；已退订的目标与已关闭的告警会被拒绝。`,
              danger: true,
              confirmText: "确认重发",
            });
            // 幂等键在点确认的瞬间生成：整条链路只认这一把键，重复提交由服务端拒绝
            if (ok) resendNotice.mutate({ noticeNo: n.noticeNo, idempotencyKey: `ANR-${n.noticeNo}-${Date.now()}` });
          }}
        >
          重发
        </Button>
      ),
    },
  ];

  const ruleCols: Column<AlarmRule>[] = [
    { header: "规则号", cell: (r) => <span className="txt-strong tabular-nums">{r.ruleNo}</span> },
    {
      header: "告警代码",
      cell: (r) => (
        <>
          <span className="tabular-nums">{r.alarmCode}</span>
          {codes.get(r.alarmCode) && <span className="block txt-caption text-muted-foreground">{codes.get(r.alarmCode)!.message}</span>}
        </>
      ),
    },
    { header: "通知目标", cell: (r) => r.target },
    { header: "渠道", cell: (r) => <Badge tone="outline">{CHANNEL_LABEL[r.channel]}</Badge> },
    { header: "方式", cell: (r) => r.method === "INSTANT" ? "即时" : "汇总" },
    // 静默窗口 / 升级策略：防夜间轰炸与告警风暴（比竞品多）
    { header: "静默窗口", cell: (r) => r.quietStart && r.quietEnd ? <span className="tabular-nums">{r.quietStart} - {r.quietEnd}</span> : <span className="text-muted-foreground">不静默</span> },
    { header: "升级策略", cell: (r) => r.escalateMinutes > 0 ? <span className="tabular-nums">{r.escalateMinutes} 分钟未处理升级</span> : <span className="text-muted-foreground">不升级</span> },
    { header: "状态", cell: (r) => <EnabledBadge on={r.status === "ACTIVE"} /> },
    ...(showArchived ? [{ header: "归档时间", cell: (r: AlarmRule) => <ArchivedAt at={r.archivedAt} /> }] : []),
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
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "records" && <AlarmRecordsTab codes={codes} onOpen={openDetail} />}
      {tab === "codes" && <AlarmCodesTab />}

      {tab === "notices" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索通知号 / 告警号 / 接收人"
            onExport={() => exportCsv<AlarmNotice>("告警通知", [
              { header: "通知号", value: (n) => n.noticeNo },
              { header: "告警号", value: (n) => n.alarmNo },
              { header: "渠道", value: (n) => CHANNEL_LABEL[n.channel] },
              { header: "接收人", value: (n) => n.target },
              { header: "发送时间", value: (n) => fmtTime(n.sentAt) },
              { header: "状态", value: (n) => NOTICE_STATUS[n.status].label },
              { header: "失败原因", value: (n) => n.failReason ?? "-" },
              { header: "重发自", value: (n) => n.resendOf ?? "-" },
            ], noticesQ.data?.list ?? [])} />
          {!canResendNotice && (
            <ReadOnlyNotice what="告警通知重发" perm="workorder:alarm:notice_resend" note="失败通知只能看原因，不能补发" />
          )}
          <PagedTable query={noticesQ} paging={paging} rowKey={(n) => n.noticeNo} columns={noticeCols}
            empty="暂无告警通知——通知由「通知规则」命中告警后自动产生，先去规则页确认规则已启用。" />
        </>
      )}

      {tab === "rules" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); paging.reset(); }} searchPlaceholder="搜索规则号 / 告警代码 / 通知目标"
            onAdd={canConfig ? () => setRuleForm({ channel: "SMS", method: "INSTANT", quietStart: "22:00", quietEnd: "08:00", escalateMinutes: 60, status: "ACTIVE", alarmCode: "", target: "" }) : undefined} addLabel="新增通知规则"
            onExport={() => exportCsv<AlarmRule>("通知规则", [
              { header: "规则号", value: (r) => r.ruleNo },
              { header: "告警代码", value: (r) => r.alarmCode },
              { header: "通知目标", value: (r) => r.target },
              { header: "渠道", value: (r) => CHANNEL_LABEL[r.channel] },
              { header: "方式", value: (r) => (r.method === "INSTANT" ? "即时" : "汇总") },
              { header: "静默窗口", value: (r) => (r.quietStart && r.quietEnd ? `${r.quietStart} - ${r.quietEnd}` : "不静默") },
              { header: "升级策略", value: (r) => (r.escalateMinutes > 0 ? `${r.escalateMinutes} 分钟未处理升级` : "不升级") },
              { header: "状态", value: (r) => (r.status === "ACTIVE" ? "启用" : "停用") },
              ...archivedCsv<AlarmRule>(),
            ], rulesQ.data?.list ?? [])}>
            <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); paging.reset(); }} />
          </Toolbar>
          {/* 权限降级显式提示（§3.2）：不静默隐藏操作列，否则会被当成功能坏了 */}
          {!canConfig && (
            <ReadOnlyNotice what="告警配置" perm="workorder:alarm:config" note="不能新增、编辑或归档通知规则" />
          )}
          <PagedTable query={rulesQ} paging={paging} rowKey={(r) => r.ruleNo} columns={ruleCols} rowClassName={archivedRowClass}
            empty={showArchived ? "没有匹配的通知规则——换个关键词，或点「新增通知规则」补一条。" : "暂无在用通知规则——可能都已归档（打开「显示已归档」查看），或点「新增通知规则」为关键告警配通知目标。"} />
        </>
      )}

      {/* 通知规则 编辑抽屉 */}
      <FormDrawer open={!!ruleForm} onOpenChange={(o) => !o && setRuleForm(null)}
        titleNew="新增通知规则" titleEdit={`编辑通知规则 ${ruleForm?.ruleNo ?? ""}`} isEdit={!!ruleForm?.ruleNo}
        fields={ruleFields(codeOptions)} value={(ruleForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setRuleForm(v as Partial<AlarmRule>)}
        onSubmit={() => ruleForm && saveRule.mutate(ruleForm)} submitting={saveRule.isPending} />

      <AlarmDetailDrawer
        alarmNo={detailNo}
        codes={codes}
        initialTab={detailTab}
        onClose={closeDetail}
        onOpenAlarm={(no) => openDetail(no)}
      />

      {dialog}
    </div>
  );
}

export default function AlarmsPage() {
  return <Suspense fallback={null}><AlarmsInner /></Suspense>;
}
