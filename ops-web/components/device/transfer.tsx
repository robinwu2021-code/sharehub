"use client";

// 库存调拨作业（方案 §6.3 · 后端 InventoryController + AssetOpsController）：
// 建单（只建草稿）→ 设定逐件明细 → 发出 → 逐件签收 → 差异落资产差异表。
//
// R1：调拨状态**不进编辑表单**。此前表单里有状态下拉，于是「改个名字」和「确认收货」是同一个保存按钮；
// 现在状态只由「发出」「确认收货」两个动作推进（TRANSFER_TRANSITIONS），单头只在草稿期可改。
import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UNPAGED_SIZE } from "@/lib/constants";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { DataTable, type Column } from "@/components/ui/data-table";
import { PagedTable } from "@/components/ui/paged-table";
import { usePaging } from "@/lib/hooks/use-paging";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/textarea";
import { Notice } from "@/components/ui/notice";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { DetailHeader } from "@/components/ui/detail-header";
import { StatusStepper } from "@/components/ui/status-stepper";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { StateActions } from "@/components/state-actions";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { RefLink } from "@/components/ref-link";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import {
  canTransferAction,
  type AssetDiff, type AssetDiffStatus, type InventoryTransfer, type InvTransferReq, type ReceiveResult,
  type TransferEndpointType, type TransferItem, type TransferItemType,
} from "@/lib/types";
import { DIFF_KIND, DIFF_STATUS, ENDPOINT_TYPE_LABEL, ITEM_TYPE_LABEL, TRANSFER_STATUS } from "./device-maps";

const PERM = "device:inventory:transfer";
/** 设定明细时给出的「当前在库可调」样本条数：只是扫码前核对用的提示，不是列表。 */
const CANDIDATE_LIMIT = 12;
/** 充电宝列表接口不带状态筛选参数（契约里没有），取一页再在前端挑在库的。 */
const CANDIDATE_POOL = 50;

const ITEM_CHECKED: StatusMap<"CHECKED" | "UNCHECKED"> = {
  CHECKED: { label: "已签收", tone: "success" },
  UNCHECKED: { label: "未签收", tone: "warning" },
};

const TYPE_OPTIONS = (Object.keys(ENDPOINT_TYPE_LABEL) as TransferEndpointType[])
  .map((t) => ({ value: t, label: ENDPOINT_TYPE_LABEL[t] }));

type Ref = { value: string; label: string };

/** 调出 / 调入方编号：站点、点位给选择器（C8），仓库暂无列表接口只能手填编号。 */
const refSpec = (type: unknown, sites: Ref[], points: Ref[]): Partial<FieldDef> =>
  type === "SITE" ? { type: "select", options: [{ value: "", label: "请选择站点" }, ...sites] }
    : type === "LOCATION" ? { type: "select", options: [{ value: "", label: "请选择点位" }, ...points] }
      : { placeholder: "WH01", help: "仓库编号（后端暂无仓库列表接口，按编号填写）" };

/**
 * 调拨单单头。**没有状态、没有经办人**：状态由动作推进（R1），经办人服务端按当前登录人落。
 * 名字字段叫 fromName / toName（后端写入面），不是列表出参的 fromLocation / toLocation ——
 * 此前按出参字段名提交，后端静默忽略，调出 / 调入名称永远存不进去。
 *
 * @form POST /api/ops/inventory-transfers
 * @form POST /api/ops/inventory-transfers/{transferNo}
 */
const transferFields = (v: Record<string, unknown>, sites: Ref[], points: Ref[]): FieldDef[] => [
  { key: "itemType", label: "调拨物", type: "select", required: true, section: "调拨物",
    options: [{ value: "CABINET", label: "机柜" }, { value: "POWERBANK", label: "充电宝" }],
    help: "机柜随发货转「运输中」、签收后回「在库」；充电宝只记去向" },
  { key: "fromType", label: "调出方类型", type: "select", required: true, section: "调出方", options: TYPE_OPTIONS },
  { key: "fromRef", label: "调出方", required: true, section: "调出方", ...refSpec(v.fromType, sites, points) },
  { key: "fromName", label: "调出方名称", maxLength: 64, section: "调出方", help: "留空按所选站点 / 点位的名称自动填" },
  { key: "toType", label: "调入方类型", type: "select", required: true, section: "调入方", options: TYPE_OPTIONS },
  { key: "toRef", label: "调入方", required: true, section: "调入方", ...refSpec(v.toType, sites, points) },
  { key: "toName", label: "调入方名称", maxLength: 64, section: "调入方", help: "调入仓库时，签收的机柜会记到这个仓" },
];

/** 建单 / 改草稿单头抽屉。`value` 为 null 时关闭；带 transferNo 即编辑。 */
export function TransferForm({ value, onClose }: { value: Partial<InvTransferReq> | null; onClose: () => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<Record<string, unknown>>({});
  useEffect(() => { if (value) setForm(value as Record<string, unknown>); }, [value]);

  const sitesQ = useQuery({ queryKey: ["sites", "transfer-options"], queryFn: () => api.listSites({ size: UNPAGED_SIZE }), enabled: !!value });
  const pointsQ = useQuery({ queryKey: ["locations", "all"], queryFn: () => api.listLocations({ size: UNPAGED_SIZE }), enabled: !!value });
  const sites = useMemo(() => (sitesQ.data?.list ?? []).map((s) => ({ value: s.siteNo, label: `${s.siteNo} · ${s.name}` })), [sitesQ.data]);
  const points = useMemo(() => (pointsQ.data?.list ?? []).map((l) => ({ value: l.locationNo, label: `${l.locationNo} · ${l.name}` })), [pointsQ.data]);

  /** 名称留空时按所选站点 / 点位回填：列表上只显示名称，不填就是一格空白。 */
  const nameOf = (type: unknown, ref: string) => {
    const pool = type === "SITE" ? sitesQ.data?.list.map((s) => [s.siteNo, s.name])
      : type === "LOCATION" ? pointsQ.data?.list.map((l) => [l.locationNo, l.name]) : null;
    return pool?.find(([no]) => no === ref)?.[1] ?? ref;
  };

  const save = useMutation({
    mutationFn: () => {
      const fromRef = String(form.fromRef ?? "").trim();
      const toRef = String(form.toRef ?? "").trim();
      return api.saveInventoryTransfer({
        transferNo: value?.transferNo,
        itemType: form.itemType as TransferItemType,
        fromType: form.fromType as TransferEndpointType, fromRef,
        fromName: String(form.fromName ?? "").trim() || nameOf(form.fromType, fromRef),
        toType: form.toType as TransferEndpointType, toRef,
        toName: String(form.toName ?? "").trim() || nameOf(form.toType, toRef),
      });
    },
    onSuccess: (t) => {
      qc.invalidateQueries({ queryKey: ["devices"] });
      qc.invalidateQueries({ queryKey: ["transfer-detail", t.transferNo] });
      notify.success(value?.transferNo ? `已保存 ${t.transferNo}` : `已建草稿单 ${t.transferNo}：下一步在详情里设定逐件明细`);
      onClose();
    },
  });

  return (
    <FormDrawer
      open={!!value}
      onOpenChange={(o) => !o && onClose()}
      titleNew="新建调拨单（草稿）"
      titleEdit={`编辑调拨单 ${value?.transferNo ?? ""}`}
      isEdit={!!value?.transferNo}
      fields={transferFields(form, sites, points)}
      value={form}
      onChange={setForm}
      onSubmit={() => save.mutate()}
      submitting={save.isPending}
    />
  );
}

/**
 * 设定逐件明细（整体替换）。每件都要在库且质检已过，后端逐件校验；一件不合格整批不落。
 *
 * @form POST /api/ops/inventory-transfers/{transferNo}/items
 */
const itemsFields = (itemType: TransferItemType, hint: string): FieldDef[] => [
  { key: "itemNos", label: `${ITEM_TYPE_LABEL[itemType]}编号`, type: "textarea", required: true, rows: 8,
    placeholder: itemType === "CABINET" ? "CAB1001\nCAB1002" : "PB20001\nPB20002",
    help: `每行一个（扫码枪逐行录入即可），也可用逗号分隔；重复的自动去掉。${hint}` },
];

const splitNos = (raw: unknown) =>
  [...new Set(String(raw ?? "").split(/[\s,，;；]+/).map((s) => s.trim()).filter(Boolean))];

/** 签收面板：逐件勾选实收 + 补录单上没有的件号。缺件 / 多件照常签收，逐件落资产差异。 */
function ReceivePanel({ transfer, items, onDone }: {
  transfer: InventoryTransfer; items: TransferItem[]; onDone: (r: ReceiveResult) => void;
}) {
  const [got, setGot] = useState<Set<string>>(() => new Set(items.map((i) => i.itemNo)));
  const [extraRaw, setExtraRaw] = useState("");
  const [note, setNote] = useState("");
  const extra = splitNos(extraRaw).filter((n) => !items.some((i) => i.itemNo === n));
  const missing = items.filter((i) => !got.has(i.itemNo)).length;
  const receive = useMutation({
    mutationFn: () => api.receiveTransfer(transfer.transferNo, [...got, ...extra], note.trim() || undefined),
    onSuccess: onDone,
  });
  const toggle = (no: string, on: boolean) => setGot((s) => {
    const n = new Set(s);
    if (on) n.add(no); else n.delete(no);
    return n;
  });

  return (
    <div className="space-y-3 rounded-card bg-muted/50 p-4">
      <div className="txt-strong">逐件签收</div>
      <div className="txt-caption text-muted-foreground">
        勾掉没收到的件；单上没有但实际到了的，填进下面「多收的件号」。有差异也照常签收，缺件 / 多件会逐件记入资产差异待查。
      </div>
      {items.length > 0 && (
        <div className="flex flex-wrap items-center gap-3">
          <Button size="sm" variant="outline" onClick={() => setGot(new Set(items.map((i) => i.itemNo)))}>全部已收</Button>
          <span className="txt-caption tabular-nums text-muted-foreground">实收 {got.size} / 单上 {items.length}{missing ? ` · 缺 ${missing} 件` : ""}</span>
        </div>
      )}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
        {items.map((i) => (
          <label key={i.itemNo} className="flex cursor-pointer items-center gap-2 txt-body tabular-nums">
            <Checkbox checked={got.has(i.itemNo)} onChange={(v) => toggle(i.itemNo, v === true)} />
            {i.itemNo}
          </label>
        ))}
      </div>
      <div className="space-y-1">
        <div className="txt-label text-muted-foreground">多收的件号（单上没有）</div>
        <Textarea rows={2} value={extraRaw} onChange={(v) => setExtraRaw(v)} placeholder="每行一个，没有就留空" />
      </div>
      <div className="space-y-1">
        <div className="txt-label text-muted-foreground">签收备注</div>
        <Textarea rows={2} value={note} onChange={(v) => setNote(v)} placeholder="例：外箱破损 1 件，已拍照" />
      </div>
      {(missing > 0 || extra.length > 0) && (
        <Notice className="mb-0 bg-warning-tint text-warning-ink">
          将记 {missing} 件缺件、{extra.length} 件多件到资产差异，签收后由仓管逐件查清去向。
        </Notice>
      )}
      <div className="flex justify-end">
        <Button size="sm" disabled={receive.isPending || (items.length > 0 && got.size + extra.length === 0)}
          title={items.length > 0 && got.size + extra.length === 0 ? "一件都没勾：多半是还没扫码，而不是真的一件没到" : undefined}
          onClick={() => receive.mutate()}>
          {receive.isPending ? "签收中…" : "确认签收"}
        </Button>
      </div>
    </div>
  );
}

/** 签收结果：当场告诉人「进了哪个仓、少了哪几台」，而不是月底盘点才发现。 */
function ReceiveSummary({ r, transfer, items }: { r: ReceiveResult; transfer: InventoryTransfer; items: TransferItem[] }) {
  const cab = transfer.itemType === "CABINET";
  const receivedNos = items.map((i) => i.itemNo).filter((no) => !r.missing.includes(no));
  return (
    <Notice className={r.diffs.length ? "mb-0 bg-warning-tint text-warning-ink" : "mb-0 bg-success-tint text-success-ink"}>
      <div>
        {r.received} {cab ? "台机柜" : "件"}已签收{transfer.toLocation ? `，进入「${transfer.toLocation}」` : ""}
        {cab ? "（状态回到在库）" : ""}。
        {r.missing.length > 0 && <> 缺 {r.missing.length} 件：{r.missing.join("、")}。</>}
        {r.extra.length > 0 && <> 多 {r.extra.length} 件：{r.extra.join("、")}。</>}
        {r.diffs.length > 0 && " 差异已记入资产差异，见下方。"}
      </div>
      {cab && receivedNos.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-x-3">
          {receivedNos.map((no) => <RefLink key={no} kind="cabinet" no={no} />)}
        </div>
      )}
    </Notice>
  );
}

/** 调拨单详情抽屉：单头 + 步骤条 + 动作 + 明细 + 签收 + 该单的资产差异。 */
export function TransferDetailDrawer({ transferNo, onClose, onEdit }: {
  transferNo: string | null; onClose: () => void; onEdit: (t: InvTransferReq) => void;
}) {
  const qc = useQueryClient();
  const canTransfer = useCan()(PERM);
  const [itemsForm, setItemsForm] = useState<Record<string, unknown> | null>(null);
  const [receiving, setReceiving] = useState(false);
  const [result, setResult] = useState<ReceiveResult | null>(null);
  useEffect(() => { setReceiving(false); setResult(null); }, [transferNo]);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["transfer-detail", transferNo],
    queryFn: () => api.getInventoryTransfer(transferNo!),
    enabled: !!transferNo,
  });
  const t = data?.transfer;
  const items = data?.items ?? [];
  const itemType = (t?.itemType as TransferItemType | null) ?? "POWERBANK";

  // 候选件：在库的机柜 / 充电宝（给设定明细时参考；只列前 12 个，够扫码前核对用）
  const candQ = useQuery({
    queryKey: ["transfer-candidates", itemType],
    queryFn: async () => itemType === "CABINET"
      ? (await api.listCabinets({ status: "IN_STOCK", size: CANDIDATE_LIMIT })).list.map((c) => c.cabinetNo)
      : (await api.listPowerbanks({ size: CANDIDATE_POOL })).list.filter((p) => p.status === "IN_STOCK")
        .slice(0, CANDIDATE_LIMIT).map((p) => p.powerbankNo),
    enabled: !!itemsForm,
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["transfer-detail", transferNo] });
    qc.invalidateQueries({ queryKey: ["devices"] });
    qc.invalidateQueries({ queryKey: ["asset-diffs"] });
    qc.invalidateQueries({ queryKey: ["cabinets"] });
  };
  const setItems = useMutation({
    mutationFn: (nos: string[]) => api.setTransferItems(transferNo!, nos),
    onSuccess: (d) => { refresh(); notify.success(`明细已设定：${d.items.length} 件`); setItemsForm(null); },
  });
  const ship = useMutation({
    mutationFn: () => api.shipTransfer(transferNo!),
    onSuccess: (d) => {
      refresh();
      notify.success(`${d.transfer.transferNo} 已发出${d.transfer.itemType === "CABINET" ? `：${d.items.length} 台机柜转为运输中` : ""}`);
    },
  });

  const status = t?.status;
  const actions = t ? [
    {
      key: "edit", label: "编辑单头", perm: PERM, when: status === "DRAFT",
      onRun: () => onEdit({
        transferNo: t.transferNo, itemType: itemType,
        fromType: (t.fromType ?? "WAREHOUSE") as TransferEndpointType, fromRef: t.fromRef ?? "", fromName: t.fromLocation,
        toType: (t.toType ?? "WAREHOUSE") as TransferEndpointType, toRef: t.toRef ?? "", toName: t.toLocation,
      }),
    },
    {
      key: "items", label: "设定明细", perm: PERM, when: status === "DRAFT",
      onRun: () => setItemsForm({ itemNos: items.map((i) => i.itemNo).join("\n") }),
    },
    {
      key: "ship", label: "发出", perm: PERM, primary: true, when: !!status && canTransferAction(status, "ship"),
      blockedReason: items.length === 0 ? "还没有逐件明细：先「设定明细」，否则收货时无从核对少了哪件" : null,
      confirm: {
        title: `发出 ${t.transferNo}`,
        desc: `共 ${items.length} 件${itemType === "CABINET" ? "机柜，发出后转为「运输中」，不能再上线或被别的单调走" : "充电宝"}。发出后明细不能再改。`,
        confirmText: "发出",
      },
      onRun: () => ship.mutateAsync().catch(() => undefined),
    },
    {
      key: "receive", label: "确认收货", perm: PERM, primary: true,
      when: !!status && canTransferAction(status, "receive") && !receiving,
      onRun: () => setReceiving(true),
    },
  ] : [];

  return (
    <Drawer
      open={!!transferNo}
      onOpenChange={(o) => !o && onClose()}
      width="w-[720px]"
      title={`调拨单 ${transferNo ?? ""}`}
      desc={t ? `${t.fromLocation || t.fromRef || "-"} → ${t.toLocation || t.toRef || "-"} · ${ITEM_TYPE_LABEL[itemType]}` : undefined}
    >
      {error ? <ErrorState error={error} onRetry={refetch} /> : isLoading || !t ? <Skeleton className="h-40" /> : (
        <div className="space-y-5">
          <DetailHeader
            no={t.transferNo}
            badge={<StatusBadge map={TRANSFER_STATUS} value={t.status} />}
            meta={`${t.fromType ? ENDPOINT_TYPE_LABEL[t.fromType as TransferEndpointType] ?? t.fromType : ""} ${t.fromRef ?? ""} → ${t.toType ? ENDPOINT_TYPE_LABEL[t.toType as TransferEndpointType] ?? t.toType : ""} ${t.toRef ?? ""} · 经办 ${t.operator || "-"} · ${fmtTime(t.createdAt)}`}
            stepper={<StatusStepper steps={[{ key: "DRAFT", label: "草稿" }, { key: "IN_TRANSIT", label: "在途" }, { key: "DONE", label: "已完成" }]} current={t.status} />}
            actions={<StateActions actions={actions} />}
          />
          {!canTransfer && <ReadOnlyNotice what="调拨执行" perm={PERM} note="不能设定明细、发出或签收" />}

          {result && <ReceiveSummary r={result} transfer={t} items={items} />}
          {receiving && status === "IN_TRANSIT" && (
            <ReceivePanel transfer={t} items={items} onDone={(r) => { setResult(r); setReceiving(false); refresh(); }} />
          )}

          <div>
            {/* 单据台数与明细行数对不上，本身就是要查的线索，所以两个数都摆出来 */}
            <div className="mb-2 txt-caption text-muted-foreground">
              单据 {Math.round(t.powerbankCount)} 件 · 明细 {items.length} 件 · 已签收 {items.filter((i) => i.checked).length}
            </div>
            <DataTable
              rowKey={(i: TransferItem) => i.itemNo}
              columns={[
                { header: "设备编号", cell: (i) => (itemType === "CABINET" ? <RefLink kind="cabinet" no={i.itemNo} /> : <span className="tabular-nums">{i.itemNo}</span>) },
                { header: "签收", cell: (i) => <StatusBadge map={ITEM_CHECKED} value={i.checked ? "CHECKED" : "UNCHECKED"} /> },
              ]}
              rows={[...items].sort((a, b) => Number(a.checked) - Number(b.checked))}
              empty={status === "DRAFT"
                ? "还没有逐件明细——点「设定明细」录入要调的件号（扫码逐行录入），发出前必须有明细"
                : "这张调拨单没有逐台明细——按台数登记的旧单据不会留下明细行"}
            />
          </div>

          {status === "DONE" && (
            <div>
              <div className="mb-2 txt-strong">本单的资产差异</div>
              <AssetDiffsTable sourceRef={t.transferNo} compact />
            </div>
          )}
        </div>
      )}
      <FormDrawer
        open={!!itemsForm}
        onOpenChange={(o) => !o && setItemsForm(null)}
        titleNew={`设定明细 ${transferNo ?? ""}`}
        titleEdit={`设定明细 ${transferNo ?? ""}`}
        isEdit={false}
        fields={itemsFields(itemType, candQ.data?.length ? `当前在库可调：${candQ.data.join("、")}${candQ.data.length >= CANDIDATE_LIMIT ? " 等" : ""}` : "")}
        value={itemsForm ?? {}}
        onChange={setItemsForm}
        onSubmit={() => itemsForm && setItems.mutate(splitNos(itemsForm.itemNos))}
        submitting={setItems.isPending}
      />
    </Drawer>
  );
}

/**
 * 处理一条资产差异：写明去向结论（找到了 / 确认丢失已报损 / 系统记错…）。
 *
 * @form POST /api/ops/asset-diffs/{diffNo}/resolve
 */
const resolveFields: FieldDef[] = [
  { key: "note", label: "处理结论", type: "textarea", required: true, maxLength: 512, rows: 4,
    help: "写清这件东西最后在哪 / 为什么对不上。没有结论的「已处理」等于把差异藏起来" },
];

/** 资产差异列表 + 处理。`sourceRef` 给了就只看这一张单的。 */
export function AssetDiffsTable({ sourceRef, compact }: { sourceRef?: string; compact?: boolean }) {
  const qc = useQueryClient();
  const canTransfer = useCan()(PERM);
  const [status, setStatus] = useState<string>(compact ? "" : "OPEN");
  const paging = usePaging();
  const [resolving, setResolving] = useState<AssetDiff | null>(null);
  const [form, setForm] = useState<Record<string, unknown>>({});

  const query = useQuery({
    queryKey: ["asset-diffs", sourceRef ?? "", status, paging.page, paging.size],
    queryFn: () => api.listAssetDiffs({
      page: paging.page, size: paging.size, status: (status || undefined) as AssetDiffStatus | undefined, sourceRef,
    }),
  });
  const resolve = useMutation({
    mutationFn: (v: { diffNo: string; note: string }) => api.resolveAssetDiff(v.diffNo, v.note),
    onSuccess: (d) => {
      qc.invalidateQueries({ queryKey: ["asset-diffs"] });
      notify.success(`${d.diffNo} 已处理`);
      setResolving(null);
    },
  });

  const cols: Column<AssetDiff>[] = [
    { header: "差异号", cell: (d) => <span className="tabular-nums">{d.diffNo}</span> },
    { header: "种类", cell: (d) => <StatusBadge map={DIFF_KIND} value={d.kind} /> },
    {
      header: "件号",
      cell: (d) => (d.itemNo
        ? (d.itemType === "CABINET" ? <RefLink kind="cabinet" no={d.itemNo} /> : <span className="tabular-nums">{d.itemNo}</span>)
        : <span className="txt-caption text-muted-foreground">应 {d.expectedQty ?? "-"} / 实 {d.actualQty ?? "-"}{d.cabinetNo ? ` · ${d.cabinetNo}` : ""}</span>),
    },
    ...(sourceRef ? [] : [{
      header: "来源",
      cell: (d: AssetDiff) => (d.sourceType === "TRANSFER"
        ? <RefLink kind="transfer" no={d.sourceRef} />
        : <span className="tabular-nums">{d.sourceRef}</span>),
    }]),
    { header: "状态", cell: (d) => <StatusBadge map={DIFF_STATUS} value={d.status} /> },
    {
      header: "结论",
      cell: (d) => (d.resolveNote
        ? <span className="txt-caption">{d.resolveNote}<span className="text-muted-foreground"> · {d.resolvedBy} {fmtTime(d.resolvedAt)}</span></span>
        : <span className="text-muted-foreground">-</span>),
    },
    { header: "发现时间", cell: (d) => <span className="text-muted-foreground">{fmtTime(d.createdAt)}</span> },
    {
      header: "操作",
      cell: (d) => (d.status === "OPEN"
        ? (
          <Button size="sm" variant="outline" disabled={!canTransfer}
            title={canTransfer ? undefined : `无权限（需要 ${PERM}）`}
            onClick={() => { setForm({}); setResolving(d); }}>
            处理
          </Button>
        )
        : <span className="text-muted-foreground">-</span>),
    },
  ];

  return (
    <div className="space-y-3">
      {!compact && (
        <div className="flex flex-wrap items-center gap-2">
          <FilterSelect value={status} onChange={(v) => { setStatus(v); paging.reset(); }} options={DIFF_STATUS}
            allLabel="全部状态" aria-label="按处理状态筛选" />
          <span className="txt-caption text-muted-foreground">调拨签收的缺件 / 多件与撤机清点数不符都记在这里，逐条查清去向。</span>
        </div>
      )}
      <PagedTable
        query={query}
        paging={paging}
        rowKey={(d: AssetDiff) => d.diffNo}
        columns={cols}
        empty={sourceRef
          ? "这张单签收时件数全对，没有差异"
          : status === "OPEN" ? "没有待查清的差异——调拨签收与撤机清点都对上了" : "还没有产生过资产差异"}
      />
      <FormDrawer
        open={!!resolving}
        onOpenChange={(o) => !o && setResolving(null)}
        titleNew={`处理差异 ${resolving?.diffNo ?? ""}`}
        titleEdit={`处理差异 ${resolving?.diffNo ?? ""}`}
        isEdit={false}
        fields={resolveFields}
        value={form}
        onChange={setForm}
        onSubmit={() => resolving && resolve.mutate({ diffNo: resolving.diffNo, note: String(form.note ?? "").trim() })}
        submitting={resolve.isPending}
      />
    </div>
  );
}
