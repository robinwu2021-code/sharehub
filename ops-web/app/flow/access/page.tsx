"use client";

// 接入管理（原型 P7，平台管理员）：接入应用 · webhook 投递 · 定义版本。
// 凭证只在创建 / 轮换时显示一次（服务端之后读不到明文），关掉弹窗就再也看不到 —— 界面上明说。
import { NeedPerm } from "@/components/flow/need-perm";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Plus } from "lucide-react";
import { flow, FLOW_KEY } from "@/lib/flow/client";
import { useFlowT, type FlowKey } from "@/lib/flow/i18n";
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

type TT = (k: FlowKey<"access">) => string;
const appStatusMap = (tt: TT): StatusMap<"on" | "off"> => ({ on: { label: tt("appStatus.on"), tone: "success" }, off: { label: tt("appStatus.off"), tone: "muted" } });
const deliveryMap = (tt: TT): StatusMap<DeliveryRow["status"]> => ({
  DELIVERED: { label: tt("delivery.DELIVERED"), tone: "success" }, RETRYING: { label: tt("delivery.RETRYING"), tone: "warning" }, FAILED: { label: tt("delivery.FAILED"), tone: "danger" },
});

/**
 * @form-none 新增应用提交到 OpsKit（POST /accounts/{a}/apps，21/06 §三），不是 ShareHub 后端，contract.json 管不到。
 */
const appFields = (tt: TT): FieldDef[] => [
  { key: "code", label: tt("field.code.label"), required: true, help: tt("field.code.help") },
  { key: "name", label: tt("field.name.label"), required: true, maxLength: 60 },
  { key: "callbackUrl", label: tt("field.callbackUrl.label"), required: true, pattern: { re: "^https://\\S+$", msg: tt("field.callbackUrl.pattern") }, help: tt("field.callbackUrl.help") },
  { key: "timeZone", label: tt("field.timeZone.label"), required: true, help: tt("field.timeZone.help") },
];

function FlowAccess() {
  const qc = useQueryClient();
  const { t: tt, tc, fmt } = useFlowT("access");
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
    onError: (e: Error) => notify.error(fmt.fail(e)),
  });
  const rotate = useMutation({
    mutationFn: (a: AppRow) => flow.rotateCredential(a.id),
    onSuccess: (r, a) => setSecret({ app: a.code, value: r.secret }),
    onError: (e: Error) => notify.error(e.message),
  });
  const redeliver = useMutation({
    mutationFn: (d: DeliveryRow) => flow.redeliver(d.id),
    onSuccess: (d) => { notify.success(tt("redeliver.done", { event: d.eventType, status: String(d.httpStatus ?? "") })); refresh(); },
    onError: (e: Error) => notify.error(e.message),
  });

  const appCols: Column<AppRow>[] = [
    { header: tt("col.code"), pk: true, cell: (a) => a.code },
    { header: tt("col.name"), cell: (a) => a.name },
    { header: tt("col.callbackUrl"), cell: (a) => <span className="break-all text-muted-foreground">{a.callbackUrl}</span> },
    { header: tt("col.timeZone"), cell: (a) => a.timeZone },
    { header: tt("col.status"), cell: (a) => <StatusBadge map={appStatusMap(tt)} value={a.enabled ? "on" : "off"} /> },
    {
      header: tt("col.action"), className: "text-end",
      cell: (a) => (
        <Button size="sm" variant="secondary" loading={rotate.isPending && rotate.variables?.id === a.id}
          onClick={async () => {
            if (await confirm({ title: tt("rotate.title", { name: a.name }), desc: tt("rotate.desc"), confirmText: tt("rotate.confirm"), danger: true })) rotate.mutate(a);
          }}>
          {tt("rotate.btn")}
        </Button>
      ),
    },
  ];
  const delCols: Column<DeliveryRow>[] = [
    { header: tt("col.event"), cell: (d) => <span className="font-mono text-xs">{d.eventType}</span> },
    { header: tt("col.app"), cell: (d) => d.app },
    { header: tt("col.idemKey"), cell: (d) => <span className="font-mono text-xs">{d.idemKey}</span> },
    { header: tt("col.status"), cell: (d) => (
      <span className="inline-flex items-center gap-1.5">
        <StatusBadge map={deliveryMap(tt)} value={d.status} />
        <span className="txt-caption text-muted-foreground">{d.httpStatus ?? ""}{d.error ? ` ${d.error}` : ""}</span>
      </span>
    ) },
    { header: tt("col.duration"), className: "text-end", cell: (d) => <span className="tabular-nums">{d.durationMs != null ? `${d.durationMs}ms` : tc("none")}</span> },
    { header: tt("col.attempts"), className: "text-end", cell: (d) => <span className="tabular-nums">{d.attempts} / {d.maxAttempts}</span> },
    { header: tt("col.time"), cell: (d) => <span className="tabular-nums">{fmt.short(d.atMs)}</span> },
    {
      header: tt("col.action"), className: "text-end",
      cell: (d) => d.status === "FAILED" ? (
        <Button size="sm" variant="secondary" loading={redeliver.isPending && redeliver.variables?.id === d.id} onClick={() => redeliver.mutate(d)}>{tt("redeliver.btn")}</Button>
      ) : null,
    },
  ];
  const defCols: Column<DefinitionVersionRow>[] = [
    { header: tt("col.kind"), cell: (d) => tt(`kind.${d.kind}`) },
    { header: tt("col.code"), pk: true, cell: (d) => d.code },
    { header: tt("col.version"), cell: (d) => <span className="font-mono text-xs">{d.version}</span> },
    { header: tt("col.publishedAt"), cell: (d) => <span className="tabular-nums">{fmt.short(d.publishedAtMs)}</span> },
    { header: tt("col.publishedBy"), cell: (d) => (d.channel === "CONSOLE" ? tt("publishedByConsole", { by: d.publishedBy }) : d.publishedBy) },
  ];

  return (
    <div>
      <PageTitle title={tc("page.access")} desc={tt("desc")} />

      <Card className="mb-4 p-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="txt-strong">{tt("apps.heading")}</h2>
          <Button size="sm" onClick={() => { setForm({ code: "", name: "", callbackUrl: "https://", timeZone: "Asia/Dubai" }); setCreating(true); }}>
            <Plus className="size-4" /> {tt("apps.add")}
          </Button>
        </div>
        <DataTable columns={appCols} rows={apps.data} loading={apps.isLoading} error={apps.error} onRetry={() => apps.refetch()}
          rowKey={(a) => a.id} empty={tt("apps.empty")} />
      </Card>

      <Card className="mb-4 p-5">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="txt-strong">{tt("deliveries.heading")}</h2>
          <span className="txt-caption text-muted-foreground">{tt("deliveries.hint")}</span>
        </div>
        <DataTable columns={delCols} rows={dels.data} loading={dels.isLoading} error={dels.error} onRetry={() => dels.refetch()}
          rowKey={(d) => d.id} empty={tt("deliveries.empty")} />
      </Card>

      <Card className="p-5">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="txt-strong">{tt("defs.heading")}</h2>
          <span className="txt-caption text-muted-foreground">{tt("defs.hint")}</span>
        </div>
        <DataTable columns={defCols} rows={defs.data} loading={defs.isLoading} error={defs.error} onRetry={() => defs.refetch()}
          rowKey={(d) => `${d.kind}:${d.code}`} empty={tt("defs.empty")} />
      </Card>

      <FormDrawer open={creating} onOpenChange={setCreating} titleNew={tt("apps.createTitle")} titleEdit="" isEdit={false}
        fields={appFields(tt)} value={form} onChange={setForm} onSubmit={() => create.mutate()} submitting={create.isPending} />

      <Drawer open={!!secret} onOpenChange={(o) => { if (!o) setSecret(null); }} title={tt("secret.title", { app: secret?.app ?? "" })}
        footer={<Button onClick={() => setSecret(null)}>{tt("secret.close")}</Button>}>
        <Notice>{tt("secret.notice")}</Notice>
        <div className="flex items-center gap-2 rounded-field border border-[var(--border-strong)] bg-card p-3">
          <code className="min-w-0 flex-1 break-all font-mono text-sm">{secret?.value}</code>
          <Button size="icon" variant="ghost" aria-label={tt("secret.copyAria")}
            onClick={async () => { try { await navigator.clipboard.writeText(secret?.value ?? ""); notify.success(tt("secret.copied")); } catch { notify.error(tt("secret.copyFailed")); } }}>
            <Copy />
          </Button>
        </div>
      </Drawer>
      {dialog}
    </div>
  );
}

export default function FlowAccessPage() {
  return <NeedPerm code="flow:access:manage" page="access"><FlowAccess /></NeedPerm>;
}
