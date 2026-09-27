"use client";

// 接入管理（原型 P7，平台管理员）：接入应用 · webhook 投递 · 定义版本。
// 凭证只在创建 / 轮换时显示一次（服务端之后读不到明文），关掉弹窗就再也看不到 —— 界面上明说。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Plus } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { failText, fmtShort } from "@/lib/flow/present";
import type { AppRow, DefinitionVersionRow, DeliveryRow } from "@/lib/flow/types";
import { notify } from "@/lib/notify";
import { PageTitle } from "@/components/ui/misc";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Drawer } from "@/components/ui/drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";

const APP_STATUS: StatusMap<"on" | "off"> = { on: { label: "启用", tone: "success" }, off: { label: "已停用", tone: "muted" } };
const DELIVERY: StatusMap<DeliveryRow["status"]> = {
  DELIVERED: { label: "成功", tone: "success" }, RETRYING: { label: "重试中", tone: "warning" }, FAILED: { label: "失败", tone: "danger" },
};
const KIND: Record<DefinitionVersionRow["kind"], string> = { PROCESS: "审批流程", TICKET_TYPE: "工单类型", STATE_MACHINE: "状态图" };

/**
 * @form-none 新增应用提交到 OpsKit（POST /accounts/{a}/apps，21/06 §三），不是 ShareHub 后端，contract.json 管不到。
 */
const APP_FIELDS: FieldDef[] = [
  { key: "code", label: "代号", required: true, help: "大写字母开头，如 SHAREHUB；创建后不能改" },
  { key: "name", label: "名称", required: true, maxLength: 60 },
  { key: "callbackUrl", label: "回调地址", required: true, pattern: { re: "^https://\\S+$", msg: "必须是 https 地址" }, help: "平台把事件推到这里（带签名）" },
  { key: "timeZone", label: "时区", required: true, help: "IANA 时区，如 Asia/Dubai；影响该应用的营业时间与统计口径" },
];

export default function FlowAccessPage() {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const refresh = () => qc.invalidateQueries({ queryKey: FLOW_KEY });
  const apps = useQuery({ queryKey: [...FLOW_KEY, "apps"], queryFn: flow.apps });
  const dels = useQuery({ queryKey: [...FLOW_KEY, "deliveries"], queryFn: flow.deliveries });
  const defs = useQuery({ queryKey: [...FLOW_KEY, "definition-versions"], queryFn: flow.definitionVersions });

  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<Record<string, unknown>>({ code: "", name: "", callbackUrl: "https://", timeZone: "Asia/Dubai" });
  /** 刚拿到的明文凭证：只在这个弹窗里出现一次 */
  const [secret, setSecret] = useState<{ app: string; value: string } | null>(null);

  const create = useMutation({
    mutationFn: () => flow.createApp({ code: String(form.code ?? "").toUpperCase(), name: String(form.name ?? ""), callbackUrl: String(form.callbackUrl ?? ""), timeZone: String(form.timeZone ?? "") }),
    onSuccess: (r) => { setCreating(false); setSecret({ app: r.app.code, value: r.secret }); refresh(); },
    onError: (e: Error) => notify.error(failText(e)),
  });
  const rotate = useMutation({
    mutationFn: (a: AppRow) => flow.rotateCredential(a.id),
    onSuccess: (r, a) => setSecret({ app: a.code, value: r.secret }),
    onError: (e: Error) => notify.error(e.message),
  });
  const redeliver = useMutation({
    mutationFn: (d: DeliveryRow) => flow.redeliver(d.id),
    onSuccess: (d) => { notify.success(`已重投 ${d.eventType}：对端返回 ${d.httpStatus}`); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });

  const appCols: Column<AppRow>[] = [
    { header: "代号", pk: true, cell: (a) => a.code },
    { header: "名称", cell: (a) => a.name },
    { header: "回调地址", cell: (a) => <span className="break-all text-muted-foreground">{a.callbackUrl}</span> },
    { header: "时区", cell: (a) => a.timeZone },
    { header: "状态", cell: (a) => <StatusBadge map={APP_STATUS} value={a.enabled ? "on" : "off"} /> },
    {
      header: "操作", className: "text-end",
      cell: (a) => (
        <Button size="sm" variant="secondary" loading={rotate.isPending && rotate.variables?.id === a.id}
          onClick={async () => {
            if (await confirm({ title: `轮换「${a.name}」的凭证？`, desc: "旧凭证立即失效，该应用在换上新凭证前的调用都会被拒。新凭证只显示一次。", confirmText: "轮换", danger: true })) rotate.mutate(a);
          }}>
          轮换凭证
        </Button>
      ),
    },
  ];
  const delCols: Column<DeliveryRow>[] = [
    { header: "事件", cell: (d) => <span className="font-mono text-xs">{d.eventType}</span> },
    { header: "应用", cell: (d) => d.app },
    { header: "幂等键", cell: (d) => <span className="font-mono text-xs">{d.idemKey}</span> },
    { header: "状态", cell: (d) => (
      <span className="inline-flex items-center gap-1.5">
        <StatusBadge map={DELIVERY} value={d.status} />
        <span className="txt-caption text-muted-foreground">{d.httpStatus ?? ""}{d.error ? ` ${d.error}` : ""}</span>
      </span>
    ) },
    { header: "耗时", className: "text-end", cell: (d) => <span className="tabular-nums">{d.durationMs != null ? `${d.durationMs}ms` : "—"}</span> },
    { header: "次数", className: "text-end", cell: (d) => <span className="tabular-nums">{d.attempts} / {d.maxAttempts}</span> },
    { header: "时间", cell: (d) => <span className="tabular-nums">{fmtShort(d.atMs)}</span> },
    {
      header: "操作", className: "text-end",
      cell: (d) => d.status === "FAILED" ? (
        <Button size="sm" variant="secondary" loading={redeliver.isPending && redeliver.variables?.id === d.id} onClick={() => redeliver.mutate(d)}>立即重投</Button>
      ) : null,
    },
  ];
  const defCols: Column<DefinitionVersionRow>[] = [
    { header: "类别", cell: (d) => KIND[d.kind] },
    { header: "代号", pk: true, cell: (d) => d.code },
    { header: "当前版本", cell: (d) => <span className="font-mono text-xs">{d.version}</span> },
    { header: "登记时间", cell: (d) => <span className="tabular-nums">{fmtShort(d.atMs)}</span> },
    { header: "登记方", cell: (d) => d.by },
  ];

  return (
    <div>
      <PageTitle title="接入管理" desc="平台管理员：接入应用 · webhook 订阅与投递 · 定义版本。凭证只在创建时显示一次。" />

      <Card className="mb-4 p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">接入应用</h2>
          <Button size="sm" onClick={() => { setForm({ code: "", name: "", callbackUrl: "https://", timeZone: "Asia/Dubai" }); setCreating(true); }}>
            <Plus className="size-4" /> 新增应用
          </Button>
        </div>
        <DataTable columns={appCols} rows={apps.data} loading={apps.isLoading} error={apps.error} onRetry={() => apps.refetch()}
          rowKey={(a) => a.id} empty="还没有接入应用" />
      </Card>

      <Card className="mb-4 p-5">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="txt-strong">Webhook 投递（最近）</h2>
          <span className="txt-caption text-muted-foreground">重试：1m · 5m · 30m · 2h · 12h · 24h，共 8 次；同一实例 / 工单内按 seq 判序</span>
        </div>
        <DataTable columns={delCols} rows={dels.data} loading={dels.isLoading} error={dels.error} onRetry={() => dels.refetch()}
          rowKey={(d) => d.id} empty="最近没有投递记录" />
      </Card>

      <Card className="p-5">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="txt-strong">定义版本</h2>
          <span className="txt-caption text-muted-foreground">内容哈希即版本；进行中的实例按提交时版本走完</span>
        </div>
        <DataTable columns={defCols} rows={defs.data} loading={defs.isLoading} error={defs.error} onRetry={() => defs.refetch()}
          rowKey={(d) => `${d.kind}:${d.code}`} empty="还没有登记任何定义" />
      </Card>

      <FormDrawer open={creating} onOpenChange={setCreating} titleNew="新增接入应用" titleEdit="" isEdit={false}
        fields={APP_FIELDS} value={form} onChange={setForm} onSubmit={() => create.mutate()} submitting={create.isPending} />

      <Drawer open={!!secret} onOpenChange={(o) => { if (!o) setSecret(null); }} title={`${secret?.app ?? ""} 的新凭证`}
        footer={<Button onClick={() => setSecret(null)}>我已保存，关闭</Button>}>
        <Notice>这是唯一一次显示明文。关掉后平台也读不出来，丢了只能再轮换一次。</Notice>
        <div className="flex items-center gap-2 rounded-field border border-[var(--border-strong)] bg-card p-3">
          <code className="min-w-0 flex-1 break-all font-mono text-sm">{secret?.value}</code>
          <Button size="icon" variant="ghost" aria-label="复制凭证"
            onClick={async () => { try { await navigator.clipboard.writeText(secret?.value ?? ""); notify.success("已复制"); } catch { notify.error("复制失败，请手动选中复制"); } }}>
            <Copy />
          </Button>
        </div>
      </Drawer>
      {dialog}
    </div>
  );
}
