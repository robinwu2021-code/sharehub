"use client";

import { Suspense, useEffect, useState, useMemo } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { Select } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Progress } from "@/components/ui/progress";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { Drawer } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, ArchiveActions, ArchivedAt, archivedRowClass,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { fmtTime, cn } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { parseImport, templateCsv, type ImportColumn, type RowError } from "@/lib/import-csv";
import { SiteMap, type MapPoint } from "@/components/ui/site-map";
import type {
  Cabinet, Powerbank, CabinetMonitor, CommandRecord, InventoryTransfer, OtaRollout, PageResult,
  DeviceLog, DeviceCodeBatch,
} from "@/lib/types";

const SIZE = 10;
const TABS = [
  { key: "cabinets", label: "机柜" },
  { key: "powerbanks", label: "充电宝管理" },
  { key: "monitor", label: "实时监控" },
  { key: "commands", label: "远程指令记录" },
  { key: "logs", label: "设备日志", phase: 2 as const },
  { key: "inventory", label: "库存调拨", phase: 2 as const },
  { key: "codes", label: "设备编码", phase: 2 as const },
  { key: "ota", label: "固件 OTA", phase: 2 as const },
];
// 自建 tab（自带筛选器与查询），不走页面共用的 keyword/Toolbar/分页那一套
const STANDALONE_TABS = ["cabinets", "logs", "codes"];

type Tone = "default" | "success" | "warning" | "danger" | "muted" | "outline";

// ============================================================================
// G2 导入：机柜台账（全平台唯一的导入口，规格 §10.2）
// 四步：上传 → 预览前 10 行 → 校验报错（逐行指出第几行哪个字段错）→ 确认导入。
// **必须先整批校验通过才允许落库**——不允许「导一半失败」留下半截台账。
// ============================================================================
const IMPORT_SAMPLE = {
  柜机号: "CAB2000", SN: "SN95000", 供应商: "cd-tech", 型号: "X6",
  点位编号: "LOC200", 仓位数: "8", 固件版本: "1.4.0",
};

/** 列定义依赖「已接入供应商」「已建点位」两份主数据做存在性校验，故做成工厂函数。 */
const importColumns = (vendorCodes: string[], locationNos: string[]): ImportColumn<Partial<Cabinet>>[] => [
  { header: "柜机号", key: "cabinetNo", required: true, unique: true,
    validate: (v) => (/^CAB\d+$/.test(String(v)) ? null : "格式应为 CAB + 数字，如 CAB2000") },
  { header: "SN", key: "sn", required: true, unique: true,
    validate: (v) => (String(v).length >= 4 ? null : "出厂序列号至少 4 位") },
  { header: "供应商", key: "vendorCode", required: true,
    validate: (v) => (vendorCodes.includes(String(v)) ? null : `未接入的供应商，可选：${vendorCodes.join(" / ")}`) },
  { header: "型号", key: "model", required: true },
  // 点位可留空（到货未上架的机柜就是没点位），但填了就必须是真实存在的点位编号，
  // 否则台账里会出现「点进去查无此点位」的悬空引用。
  { header: "点位编号", key: "locationNo",
    validate: (v) => (locationNos.includes(String(v)) ? null : "点位编号不存在，请先在「场所管理 · 点位」建档") },
  { header: "仓位数", key: "slotTotal", required: true,
    parse: (raw) => { const n = Number(raw); if (!Number.isInteger(n)) throw new Error("必须是整数"); return n; },
    validate: (v) => ((v as number) >= 1 && (v as number) <= 48 ? null : "应在 1~48 之间") },
  { header: "固件版本", key: "fwVersion" },
];

type ImportStep = "upload" | "review";

function ImportCabinetsDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const [step, setStep] = useState<ImportStep>("upload");
  const [fileName, setFileName] = useState("");
  const [rows, setRows] = useState<Partial<Cabinet>[]>([]);
  const [errors, setErrors] = useState<RowError[]>([]);

  // 供应商 / 点位主数据：用于「填的值是否真实存在」这类跨表校验
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const pointsQ = useQuery({ queryKey: ["locations", "all"], queryFn: () => api.listLocations({ size: 999 }) });
  const cols = importColumns(
    (vendorsQ.data ?? []).map((v) => v.vendorCode),
    (pointsQ.data?.list ?? []).map((l) => l.locationNo),
  );

  const reset = () => { setStep("upload"); setFileName(""); setRows([]); setErrors([]); };
  const close = (o: boolean) => { if (!o) reset(); onOpenChange(o); };

  const doImport = useMutation({
    mutationFn: () => api.importCabinets(rows),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["cabinets"] });
      notify.success(`导入完成：新增 ${r.imported} 台，更新 ${r.updated} 台`);
      close(false);
    },
  });

  const onFile = async (file: File) => {
    setFileName(file.name);
    const parsed = parseImport<Partial<Cabinet>>(await file.text(), cols);
    setRows(parsed.rows);
    setErrors(parsed.errors);
    setStep("review");
  };

  const downloadTemplate = () => {
    const blob = new Blob([templateCsv(cols, IMPORT_SAMPLE)], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = "机柜台账导入模板.csv";
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const ok = errors.length === 0 && rows.length > 0;

  return (
    <Drawer
      open={open}
      onOpenChange={close}
      width="w-[620px]"
      title="导入机柜台账"
      desc="上传 → 预览 → 校验 → 确认。校验不通过不会写入任何数据"
      footer={
        step === "upload" ? (
          <Button size="sm" variant="secondary" onClick={() => close(false)}>取消</Button>
        ) : (
          <>
            <Button size="sm" variant="secondary" onClick={reset}>重新选择文件</Button>
            <Button size="sm" disabled={!ok || doImport.isPending} onClick={() => doImport.mutate()}>
              {doImport.isPending ? "导入中…" : `确认导入 ${rows.length} 条`}
            </Button>
          </>
        )
      }
    >
      {step === "upload" ? (
        <div className="space-y-4 text-sm">
          <div className="rounded-[var(--radius)] bg-muted p-4">
            <div className="mb-2 font-medium">文件要求</div>
            <ul className="list-inside list-disc space-y-1 text-muted-foreground">
              <li>CSV 格式，首行为表头，列名需与模板一致</li>
              <li>必填列：柜机号 · SN · 供应商 · 型号 · 仓位数</li>
              <li>柜机号格式 <span className="font-mono">CAB + 数字</span>，文件内不得重复；已存在的柜机号按更新处理</li>
              <li>点位编号可留空（到货未上架），填了则必须是已建档的点位</li>
            </ul>
          </div>
          <Button size="sm" variant="outline" onClick={downloadTemplate}>下载导入模板</Button>
          <div>
            <div className="mb-1.5 text-xs text-muted-foreground">选择 CSV 文件</div>
            <input
              type="file"
              accept=".csv,text/csv"
              className="block w-full cursor-pointer rounded-[var(--radius)] bg-secondary p-2.5 text-sm file:me-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-1.5 file:text-primary-foreground"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void onFile(f); }}
            />
          </div>
        </div>
      ) : (
        <div className="space-y-4 text-sm">
          <div className="text-muted-foreground">
            文件 <span className="font-medium text-foreground">{fileName}</span> ·
            解析成功 <span className="tabular-nums font-medium text-foreground">{rows.length}</span> 条 ·
            错误 <span className="tabular-nums font-medium text-foreground">{errors.length}</span> 处
          </div>

          {errors.length > 0 ? (
            <div className="rounded-[var(--radius)] bg-destructive/10 p-4">
              <div className="mb-2 font-medium text-[var(--destructive)]">
                校验未通过，本次不会写入任何数据
              </div>
              <div className="max-h-60 space-y-1 overflow-y-auto">
                {errors.map((e, i) => (
                  <div key={i} className="tabular-nums">
                    第 {e.line} 行{e.header && <> · <span className="font-medium">{e.header}</span></>}：{e.message}
                  </div>
                ))}
              </div>
              <div className="mt-2 text-xs text-muted-foreground">
                行号与 Excel 中看到的行号一致（含表头）。修正后重新上传即可。
              </div>
            </div>
          ) : (
            <div className="rounded-[var(--radius)] bg-muted p-3">校验全部通过，可以导入。</div>
          )}

          {rows.length > 0 && (
            <div>
              <div className="mb-1.5 text-xs text-muted-foreground">
                预览前 10 行（共 {rows.length} 条）
              </div>
              <div className="overflow-x-auto rounded-[var(--radius)] bg-muted/60">
                <table className="w-full text-xs">
                  <thead className="text-muted-foreground">
                    <tr>{cols.map((c) => <th key={c.header} className="px-2.5 py-2 text-start font-normal">{c.header}</th>)}</tr>
                  </thead>
                  <tbody>
                    {rows.slice(0, 10).map((r, i) => (
                      <tr key={i}>
                        {cols.map((c) => (
                          <td key={c.header} className="px-2.5 py-1.5 font-mono">
                            {String(r[c.key] ?? "-")}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}
    </Drawer>
  );
}

// —— 机柜台账：筛选/搜索/详情 + G1 归档 + G2 导入导出 + G3 批量 ——
// 批量远程指令只开这三种：都是「对整批下发同一动作」有意义的。弹仓/锁仓要指定仓位，
// 批量下发说不清对哪个仓位，故不进批量。
const BATCH_COMMANDS: { value: string; label: string; hint: string }[] = [
  { value: "REBOOT", label: "重启", hint: "重启期间约 30 秒不可借还" },
  { value: "LOCATE", label: "定位", hint: "机柜蜂鸣 3 秒，用于现场找机" },
  { value: "FW_SYNC", label: "同步固件版本", hint: "仅拉取版本号，不触发升级" },
];

function CabinetsTab({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [online, setOnline] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [cmdType, setCmdType] = useState("REBOOT");
  const [importOpen, setImportOpen] = useState(false);

  // 换页/改筛选后选中的行已不在视野内，继续留着会造成「对看不见的行动手」
  const resetPage = (fn: () => void) => { fn(); setPage(1); setSelected([]); };

  const { data, isLoading } = useQuery({
    queryKey: ["cabinets", page, keyword, online, status, showArchived],
    queryFn: () => api.listCabinets({
      page, size: SIZE, keyword,
      onlineStatus: online || undefined, status: status || undefined,
      showArchived: showArchived || undefined,
    }),
    placeholderData: keepPreviousData,
  });
  const rows = data?.list ?? [];
  const invalidate = () => qc.invalidateQueries({ queryKey: ["cabinets"] });

  const archive = useMutation({
    mutationFn: (no: string) => api.archiveCabinet(no),
    onSuccess: () => { invalidate(); notify.success("已归档"); },
  });
  const unarchive = useMutation({
    mutationFn: (no: string) => api.unarchiveCabinet(no),
    onSuccess: () => { invalidate(); notify.success("已恢复"); },
  });
  const batchArchive = useMutation({
    mutationFn: (nos: string[]) => Promise.all(nos.map((no) => api.archiveCabinet(no))),
    onSuccess: (r) => { invalidate(); setSelected([]); notify.success(`已归档 ${r.length} 台机柜`); },
  });
  const batchCommand = useMutation({
    mutationFn: (v: { nos: string[]; type: string }) =>
      Promise.all(v.nos.map((no) => api.sendCommand(no, v.type))),
    onSuccess: (r) => { setSelected([]); notify.success(`已向 ${r.length} 台机柜下发指令`); },
  });

  const onArchive = async (c: Cabinet) => {
    // 机柜是主数据：要求输入柜机号确认，防手滑归档掉在运营的机器
    if (await confirm(archiveConfirm("机柜", c.cabinetNo, c.cabinetNo))) archive.mutate(c.cabinetNo);
  };
  const onUnarchive = async (c: Cabinet) => {
    if (await confirm(unarchiveConfirm("机柜", c.cabinetNo))) unarchive.mutate(c.cabinetNo);
  };
  const onBatchArchive = async () => {
    const ok = await confirm({
      title: `批量归档 ${selected.length} 台机柜`,
      desc: `将归档已选的 ${selected.length} 台机柜：${selected.slice(0, 5).join("、")}${selected.length > 5 ? " 等" : ""}。归档后不再出现在默认列表，历史订单与告警全部保留，可随时恢复。`,
      danger: true, confirmText: "归档", requireText: String(selected.length),
    });
    if (ok) batchArchive.mutate(selected);
  };
  const onBatchCommand = async () => {
    const cmd = BATCH_COMMANDS.find((c) => c.value === cmdType)!;
    const offline = rows.filter((r) => selected.includes(r.cabinetNo) && r.onlineStatus === "OFFLINE").length;
    const ok = await confirm({
      title: `批量下发「${cmd.label}」指令`,
      desc: `将对已选的 ${selected.length} 台机柜下发「${cmd.label}」指令。${cmd.hint}。`
        + (offline > 0 ? `其中 ${offline} 台当前离线，指令会排队等待上线后执行。` : ""),
      danger: true, confirmText: "下发",
    });
    if (ok) batchCommand.mutate({ nos: selected, type: cmdType });
  };

  const cabCols: Column<Cabinet>[] = [
    { header: "柜机号", cell: (c) => <span className="font-medium">{c.cabinetNo}</span> },
    { header: "点位", cell: (c) => <span className="text-muted-foreground">{c.locationName}</span> },
    { header: "供应商", cell: (c) => c.vendorCode },
    { header: "可借/仓位", cell: (c) => <span className="tabular-nums">{c.availableCount}/{c.slotTotal}</span> },
    { header: "在线", cell: (c) => <OnlineBadge s={c.onlineStatus} /> },
    { header: "状态", cell: (c) => <CabinetStatusBadge s={c.status} /> },
    { header: "固件", cell: (c) => <span className="text-muted-foreground">{c.fwVersion}</span> },
    { header: "最后心跳", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.lastHeartbeatAt)}</span> },
    // 归档时间列只在打开「显示已归档」时才有信息量，故随开关出现
    ...(showArchived ? [{ header: "归档时间", cell: (c: Cabinet) => <ArchivedAt at={c.archivedAt} /> }] : []),
    {
      header: "操作",
      cell: (c) => (
        <ArchiveActions
          archived={!!c.archivedAt}
          canWrite={canWrite}
          onArchive={() => void onArchive(c)}
          onUnarchive={() => void onUnarchive(c)}
          actions={
            <Link className="text-primary hover:underline" href={`/devices/detail?no=${c.cabinetNo}`}>详情</Link>
          }
        />
      ),
    },
  ];

  return (
    <div>
      <Toolbar
        search={keyword}
        onSearch={(v) => resetPage(() => setKeyword(v))}
        searchPlaceholder="搜索柜机号 / 点位"
        selectedCount={selected.length}
        onClearSelection={() => setSelected([])}
        batchActions={
          <>
            <Select className="w-44" value={cmdType} onChange={(e) => setCmdType(e.target.value)} aria-label="批量指令类型">
              {BATCH_COMMANDS.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
            </Select>
            <Button size="sm" variant="outline" onClick={() => void onBatchCommand()}>下发指令</Button>
            <Button size="sm" variant="outline" onClick={() => void onBatchArchive()}>批量归档</Button>
          </>
        }
        onAdd={canWrite ? () => setImportOpen(true) : undefined}
        addLabel="导入台账"
        onExport={() => exportCsv<Cabinet>("机柜台账", [
          { header: "柜机号", value: (c) => c.cabinetNo },
          { header: "SN", value: (c) => c.sn },
          { header: "点位", value: (c) => c.locationName },
          { header: "点位编号", value: (c) => c.locationNo },
          { header: "供应商", value: (c) => c.vendorCode },
          { header: "型号", value: (c) => c.model },
          { header: "可借", value: (c) => c.availableCount },
          { header: "仓位数", value: (c) => c.slotTotal },
          { header: "在线", value: (c) => (c.onlineStatus === "ONLINE" ? "在线" : "离线") },
          { header: "状态", value: (c) => ({ DEPLOYED: "在用", FAULT: "故障", RETIRED: "报废" })[c.status] },
          { header: "固件", value: (c) => c.fwVersion },
          { header: "最后心跳", value: (c) => c.lastHeartbeatAt },
          { header: "归档时间", value: (c) => c.archivedAt },
        ], rows)}
      >
        <Select value={online} onChange={(e) => resetPage(() => setOnline(e.target.value))}>
          <option value="">全部在线态</option>
          <option value="ONLINE">在线</option>
          <option value="OFFLINE">离线</option>
        </Select>
        <Select value={status} onChange={(e) => resetPage(() => setStatus(e.target.value))}>
          <option value="">全部状态</option>
          <option value="DEPLOYED">在用</option>
          <option value="FAULT">故障</option>
          <option value="RETIRED">报废</option>
        </Select>
        <ShowArchivedToggle checked={showArchived} onChange={(v) => resetPage(() => setShowArchived(v))} />
      </Toolbar>

      {!canWrite && (
        <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">
          仅可查看：当前角色无机柜维护权限（device:cabinet:update），归档与导入不可用
        </div>
      )}

      <DataTable
        rowKey={(c: Cabinet) => c.cabinetNo}
        columns={cabCols}
        rows={data?.list}
        loading={isLoading}
        rowClassName={archivedRowClass}
        selectable={canWrite}
        selectedKeys={selected}
        onSelectedChange={setSelected}
        empty="暂无机柜——可通过右上角「导入台账」批量建档，或确认筛选条件是否过窄（已归档的机柜需勾选「显示已归档」才会出现）"
      />
      {data && <Pagination page={page} size={SIZE} total={data.total} onPage={setPage} />}

      <ImportCabinetsDrawer open={importOpen} onOpenChange={setImportOpen} />
      {dialog}
    </div>
  );
}

// —— 设备日志（规格 §1）：双流合一（COMMAND 下发 / REPORT 上报）——
// 竞品只有设备上报；两条流放同一时间轴后，「下发了什么 → 设备回了什么」的因果一眼可见。
const LOG_STREAM: Record<DeviceLog["stream"], { label: string; tone: Tone }> = {
  COMMAND: { label: "↓ 下发", tone: "default" }, // primary 色标
  REPORT: { label: "↑ 上报", tone: "muted" },
};
const LOG_RESULT: Record<DeviceLog["result"], { label: string; tone: Tone }> = {
  OK: { label: "成功", tone: "success" },
  TIMEOUT: { label: "超时", tone: "warning" },
  FAILED: { label: "失败", tone: "danger" },
};
// 事件类型走「已知才翻译，未知原样显示」——厂商随时可能上报新事件，不能因此显示空白
const LOG_EVENT: Record<string, string> = {
  EJECT: "弹仓", LOCK: "锁仓", REBOOT: "重启", FW_UPGRADE: "固件升级", LOCATE: "定位",
  HEARTBEAT: "心跳", SLOT_STATE: "仓位状态", RETURN_DETECT: "归还检测", BATTERY_LOW: "低电量", FAULT: "故障",
};

function LogsTab() {
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [stream, setStream] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const reset = () => setPage(1);

  const { data, isLoading } = useQuery({
    queryKey: ["device-logs", page, keyword, stream, from, to],
    queryFn: () => api.listDeviceLogs({ page, size: SIZE, keyword, stream: stream || undefined, from: from || undefined, to: to || undefined }),
    placeholderData: keepPreviousData,
  });

  const cols: Column<DeviceLog>[] = [
    { header: "日志号", cell: (l) => <span className="font-medium tabular-nums">{l.logNo}</span> },
    { header: "机柜号", cell: (l) => <span className="tabular-nums">{l.cabinetNo}</span> },
    { header: "流向", cell: (l) => <Badge tone={LOG_STREAM[l.stream].tone}>{LOG_STREAM[l.stream].label}</Badge> },
    { header: "事件类型", cell: (l) => <Badge tone="outline">{LOG_EVENT[l.eventType] ?? l.eventType}</Badge> },
    // 报文行内截断；完整内容点行首箭头展开（DataTable expandable）
    { header: "报文", cell: (l) => <span className="block max-w-[20rem] truncate font-mono text-xs text-muted-foreground">{l.payload}</span> },
    { header: "厂商", cell: (l) => <span className="text-muted-foreground">{l.vendorCode}</span> },
    { header: "时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.occurredAt)}</span> },
    { header: "结果", cell: (l) => <Badge tone={LOG_RESULT[l.result].tone}>{LOG_RESULT[l.result].label}</Badge> },
  ];

  return (
    <div>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); reset(); }}
        searchPlaceholder="搜索日志号 / 机柜号 / 事件类型 / 厂商"
        onExport={() => exportCsv<DeviceLog>("设备日志", [
          { header: "日志号", value: (l) => l.logNo },
          { header: "机柜号", value: (l) => l.cabinetNo },
          { header: "流向", value: (l) => LOG_STREAM[l.stream].label },
          { header: "方向", value: (l) => l.direction },
          { header: "事件类型", value: (l) => l.eventType },
          { header: "报文", value: (l) => l.payload },
          { header: "厂商", value: (l) => l.vendorCode },
          { header: "时间", value: (l) => l.occurredAt },
          { header: "结果", value: (l) => LOG_RESULT[l.result].label },
        ], data?.list ?? [])}
      >
        <Select value={stream} onChange={(e) => { setStream(e.target.value); reset(); }}>
          <option value="">全部双流</option>
          <option value="COMMAND">指令下发</option>
          <option value="REPORT">设备上报</option>
        </Select>
        <DateInput className="w-40" aria-label="起始日期" value={from} onChange={(e) => { setFrom(e.target.value); reset(); }} />
        <DateInput className="w-40" aria-label="截止日期" value={to} onChange={(e) => { setTo(e.target.value); reset(); }} />
      </Toolbar>

      <DataTable
        rowKey={(l: DeviceLog) => l.logNo}
        columns={cols}
        rows={data?.list}
        loading={isLoading}
        empty="暂无设备日志——所选时间范围内没有指令下发与设备上报，或筛选条件过窄，可放宽日期范围重试"
        // 行展开看完整报文：排障要看全文，但全文进列表会撑爆表格
        expandable={(l) => (
          <div>
            <div className="mb-2 text-xs text-muted-foreground">
              {LOG_STREAM[l.stream].label} · {LOG_EVENT[l.eventType] ?? l.eventType} · {l.cabinetNo} · {fmtTime(l.occurredAt)}
            </div>
            <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-lg bg-card p-3 font-mono text-xs">{l.payload}</pre>
          </div>
        )}
      />
      {data && <Pagination page={page} size={SIZE} total={data.total} onPage={setPage} />}
    </div>
  );
}

// —— 设备编码（规格 §2）：按批次 + 供应商归集，跟踪贴码绑定进度 ——
const CODE_STATUS: Record<DeviceCodeBatch["status"], { label: string; tone: Tone }> = {
  PENDING: { label: "待绑定", tone: "muted" },
  PARTIAL: { label: "部分绑定", tone: "warning" },
  BOUND: { label: "已绑完", tone: "success" },
  VOID: { label: "已作废", tone: "danger" },
};
const CODE_FIELDS: FieldDef[] = [
  { key: "batchNo", label: "批次号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "批次信息" },
  { key: "vendorCode", label: "供应商", type: "select", required: true, section: "批次信息", options: [
    { value: "cd-tech", label: "cd-tech" }, { value: "sd-power", label: "sd-power" }, { value: "chargenow", label: "chargenow" },
  ] },
  { key: "codeType", label: "编码类型", type: "select", required: true, section: "批次信息", options: [
    { value: "SN", label: "出厂序列号 SN" }, { value: "QR", label: "二维码 QR" },
  ] },
  { key: "producedAt", label: "生产日期", type: "date", required: true, section: "批次信息" },
  { key: "rangeStart", label: "区间起", required: true, maxLength: 24, section: "编码区间", placeholder: "SN090000", help: "同批次编码需等长同前缀，便于按区间校验归属" },
  { key: "rangeEnd", label: "区间止", required: true, maxLength: 24, section: "编码区间", placeholder: "SN090999" },
  { key: "total", label: "总数", type: "number", required: true, min: 1, section: "编码区间" },
  { key: "bound", label: "已绑定数", type: "number", required: true, min: 0, section: "编码区间", help: "不得超过总数；由绑定动作回写，此处仅供纠偏" },
  { key: "status", label: "批次状态", type: "select", required: true, section: "编码区间", options: [
    { value: "PENDING", label: "待绑定" }, { value: "PARTIAL", label: "部分绑定" },
    { value: "BOUND", label: "已绑完" }, { value: "VOID", label: "已作废" },
  ] },
];

function CodesTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [form, setForm] = useState<Partial<DeviceCodeBatch> | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["device-code-batches", page, keyword],
    queryFn: () => api.listDeviceCodeBatches({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
  });
  const save = useMutation({
    mutationFn: (v: Partial<DeviceCodeBatch>) => api.saveDeviceCodeBatch(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["device-code-batches"] }); notify.success("保存成功"); setForm(null); },
  });

  // FieldDef 表达不了「跨字段」约束，故在提交前补校验（规格 §2）
  const submit = () => {
    if (!form) return;
    const { rangeStart = "", rangeEnd = "", total = 0, bound = 0 } = form;
    if (rangeEnd < rangeStart) { notify.error("区间止不得小于区间起"); return; }
    if (rangeStart.length !== rangeEnd.length) { notify.error("区间起止长度不一致，无法按区间判断编码归属"); return; }
    if (bound > total) { notify.error("已绑定数不得超过总数"); return; }
    save.mutate(form);
  };

  const cols: Column<DeviceCodeBatch>[] = [
    { header: "批次号", cell: (b) => <span className="font-medium tabular-nums">{b.batchNo}</span> },
    { header: "供应商", cell: (b) => b.vendorCode },
    { header: "编码类型", cell: (b) => <Badge tone="outline">{b.codeType === "QR" ? "二维码" : "序列号"}</Badge> },
    // 区间等宽字体：位数对齐才看得出连续与断档
    { header: "编码区间", cell: (b) => <span className="font-mono text-xs">{b.rangeStart} ~ {b.rangeEnd}</span> },
    { header: "已绑定/总数", cell: (b) => <Progress value={b.bound} total={b.total} /> },
    { header: "生产日期", cell: (b) => <span className="text-muted-foreground">{b.producedAt.slice(0, 10)}</span> },
    { header: "状态", cell: (b) => <Badge tone={CODE_STATUS[b.status].tone}>{CODE_STATUS[b.status].label}</Badge> },
    { header: "操作", cell: (b) => canEdit ? <Button size="sm" variant="outline" onClick={() => setForm({ ...b, producedAt: b.producedAt.slice(0, 10) })}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <div>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder="搜索批次号 / 供应商 / 编码区间"
        onAdd={canEdit ? () => setForm({ vendorCode: "cd-tech", codeType: "SN", rangeStart: "", rangeEnd: "", total: 100, bound: 0, producedAt: "", status: "PENDING" }) : undefined}
        addLabel="新增贴码批次"
        onExport={() => exportCsv<DeviceCodeBatch>("设备编码", [
          { header: "批次号", value: (b) => b.batchNo },
          { header: "供应商", value: (b) => b.vendorCode },
          { header: "编码类型", value: (b) => b.codeType },
          { header: "区间起", value: (b) => b.rangeStart },
          { header: "区间止", value: (b) => b.rangeEnd },
          { header: "总数", value: (b) => b.total },
          { header: "已绑定", value: (b) => b.bound },
          { header: "生产日期", value: (b) => b.producedAt.slice(0, 10) },
          { header: "状态", value: (b) => CODE_STATUS[b.status].label },
        ], data?.list ?? [])}
      />
      {!canEdit && <div className="mb-4 rounded-lg bg-muted px-3.5 py-2 text-sm text-muted-foreground">仅可查看：当前角色无设备编码维护权限（device:cabinet:update）</div>}
      <DataTable
        rowKey={(b: DeviceCodeBatch) => b.batchNo}
        columns={cols}
        rows={data?.list}
        loading={isLoading}
        empty="暂无贴码批次——设备到货后先在此登记编码区间，机柜建档时才能按区间校验编码归属"
      />
      {data && <Pagination page={page} size={SIZE} total={data.total} onPage={setPage} />}

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新增贴码批次"
        titleEdit={`编辑批次 ${form?.batchNo ?? ""}`}
        isEdit={!!form?.batchNo}
        fields={CODE_FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<DeviceCodeBatch>)}
        onSubmit={submit}
        submitting={save.isPending}
      />
    </div>
  );
}

// —— 状态/枚举 → 中文标签 + 色调 ——
const PB_STATUS: Record<Powerbank["status"], [string, Tone]> = {
  IN_CABINET: ["在仓", "success"],
  RENTED: ["借出中", "warning"],
  FAULT: ["故障", "danger"],
  RETIRED: ["报废", "muted"],
};
const HEALTH: Record<"OK" | "FAULT", [string, Tone]> = {
  OK: ["正常", "success"],
  FAULT: ["故障", "danger"],
};
const CMD_TYPE: Record<CommandRecord["type"], string> = {
  EJECT: "弹出", LOCK: "锁仓", REBOOT: "重启", LOCATE: "定位",
};
const CMD_STATUS: Record<CommandRecord["status"], [string, Tone]> = {
  SENT: ["已下发", "warning"],
  ACKED: ["已确认", "success"],
  TIMEOUT: ["超时", "muted"],
  FAILED: ["失败", "danger"],
};
const TRANSFER_STATUS: Record<InventoryTransfer["status"], [string, Tone]> = {
  DRAFT: ["草稿", "muted"],
  IN_TRANSIT: ["在途", "warning"],
  DONE: ["已完成", "success"],
};
const OTA_STATUS: Record<OtaRollout["status"], [string, Tone]> = {
  PENDING: ["待发布", "muted"],
  RUNNING: ["升级中", "warning"],
  DONE: ["已完成", "success"],
  ROLLBACK: ["已回滚", "danger"],
};

// —— 各扩展 tab 列定义 ——
const pbCols: Column<Powerbank>[] = [
  { header: "充电宝号", cell: (r) => <span className="font-medium">{r.powerbankNo}</span> },
  { header: "所属柜机", cell: (r) => r.cabinetNo },
  { header: "电量", cell: (r) => <span className="tabular-nums">{Math.round(r.battery)}%</span> },
  { header: "状态", cell: (r) => <Badge tone={PB_STATUS[r.status][1]}>{PB_STATUS[r.status][0]}</Badge> },
  { header: "健康", cell: (r) => <Badge tone={HEALTH[r.health][1]}>{HEALTH[r.health][0]}</Badge> },
  { header: "循环次数", cell: (r) => <span className="tabular-nums">{Math.round(r.cycles)}</span> },
];
const monCols: Column<CabinetMonitor>[] = [
  { header: "柜机号", cell: (r) => <span className="font-medium">{r.cabinetNo}</span> },
  { header: "点位", cell: (r) => <span className="text-muted-foreground">{r.locationName}</span> },
  { header: "在线", cell: (r) => r.online ? <Badge tone="success">在线</Badge> : <Badge tone="muted">离线</Badge> },
  { header: "最后心跳", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.heartbeatAt)}</span> },
  { header: "信号", cell: (r) => <span className="tabular-nums">{Math.round(r.signal)}%</span> },
  { header: "温度", cell: (r) => <span className="tabular-nums">{Math.round(r.temp)}°C</span> },
  { header: "故障数", cell: (r) => r.faultCount > 0 ? <Badge tone="danger">{Math.round(r.faultCount)}</Badge> : <span className="tabular-nums text-muted-foreground">0</span> },
];
const cmdCols: Column<CommandRecord>[] = [
  { header: "指令ID", cell: (r) => <span className="font-medium">{r.commandId}</span> },
  { header: "柜机号", cell: (r) => r.cabinetNo },
  { header: "类型", cell: (r) => <Badge tone="outline">{CMD_TYPE[r.type]}</Badge> },
  { header: "仓位", cell: (r) => <span className="tabular-nums">{r.slotIndex ?? "-"}</span> },
  { header: "状态", cell: (r) => <Badge tone={CMD_STATUS[r.status][1]}>{CMD_STATUS[r.status][0]}</Badge> },
  { header: "操作人", cell: (r) => <span className="text-muted-foreground">{r.operator}</span> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
];
const invCols: Column<InventoryTransfer>[] = [
  { header: "调拨单号", cell: (r) => <span className="font-medium">{r.transferNo}</span> },
  { header: "调出点位", cell: (r) => r.fromLocation },
  { header: "调入点位", cell: (r) => r.toLocation },
  { header: "充电宝数", cell: (r) => <span className="tabular-nums">{Math.round(r.powerbankCount)}</span> },
  { header: "状态", cell: (r) => <Badge tone={TRANSFER_STATUS[r.status][1]}>{TRANSFER_STATUS[r.status][0]}</Badge> },
  { header: "操作人", cell: (r) => <span className="text-muted-foreground">{r.operator}</span> },
  { header: "创建时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
];
const otaCols: Column<OtaRollout>[] = [
  { header: "发布单号", cell: (r) => <span className="font-medium">{r.rolloutNo}</span> },
  { header: "固件版本", cell: (r) => r.fwVersion },
  { header: "供应商", cell: (r) => r.vendorCode },
  { header: "策略", cell: (r) => <Badge tone="outline">{r.strategy === "FULL" ? "全量" : "灰度"}</Badge> },
  { header: "进度", cell: (r) => <span className="tabular-nums">{Math.round(r.progress)}%</span> },
  { header: "状态", cell: (r) => <Badge tone={OTA_STATUS[r.status][1]}>{OTA_STATUS[r.status][0]}</Badge> },
  { header: "创建时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
];

type Row = Powerbank | CabinetMonitor | CommandRecord | InventoryTransfer | OtaRollout;

// —— 可编辑实体的表单字段定义 ——
const PB_FIELDS: FieldDef[] = [
  { key: "powerbankNo", label: "充电宝号", readOnlyOnEdit: true, placeholder: "系统生成" },
  { key: "cabinetNo", label: "所属柜机", placeholder: "CAB-0001" },
  { key: "battery", label: "电量（%）", type: "number" },
  { key: "status", label: "状态", type: "select", options: [
    { value: "IN_CABINET", label: "在仓" }, { value: "RENTED", label: "借出中" },
    { value: "FAULT", label: "故障" }, { value: "RETIRED", label: "报废" },
  ] },
  { key: "health", label: "健康", type: "select", options: [
    { value: "OK", label: "正常" }, { value: "FAULT", label: "故障" },
  ] },
  { key: "cycles", label: "循环次数", type: "number" },
];
const INV_FIELDS: FieldDef[] = [
  { key: "transferNo", label: "调拨单号", readOnlyOnEdit: true, placeholder: "系统生成" },
  { key: "fromLocation", label: "调出点位" },
  { key: "toLocation", label: "调入点位" },
  { key: "powerbankCount", label: "充电宝数", type: "number" },
  { key: "status", label: "状态", type: "select", options: [
    { value: "DRAFT", label: "草稿" }, { value: "IN_TRANSIT", label: "在途" }, { value: "DONE", label: "已完成" },
  ] },
  { key: "operator", label: "操作人" },
];
const OTA_FIELDS: FieldDef[] = [
  { key: "rolloutNo", label: "发布单号", readOnlyOnEdit: true, placeholder: "系统生成" },
  { key: "fwVersion", label: "固件版本", placeholder: "v1.2.0" },
  { key: "vendorCode", label: "供应商" },
  { key: "strategy", label: "策略", type: "select", options: [
    { value: "GRAY", label: "灰度" }, { value: "FULL", label: "全量" },
  ] },
  { key: "progress", label: "进度（%）", type: "number" },
  { key: "status", label: "状态", type: "select", options: [
    { value: "PENDING", label: "待发布" }, { value: "RUNNING", label: "升级中" },
    { value: "DONE", label: "已完成" }, { value: "ROLLBACK", label: "已回滚" },
  ] },
];

// —— G2 导出：共用 Toolbar 的四个 tab 各自的 CSV 列（与表格可见列一致）——
const PB_STATUS_CSV = (s: Powerbank["status"]) => PB_STATUS[s][0];
const EXPORTS: Record<string, { name: string; run: (rows: Row[]) => void }> = {
  powerbanks: {
    name: "充电宝管理",
    run: (rows) => exportCsv<Powerbank>("充电宝管理", [
      { header: "充电宝号", value: (r) => r.powerbankNo },
      { header: "所属柜机", value: (r) => r.cabinetNo },
      { header: "电量(%)", value: (r) => Math.round(r.battery) },
      { header: "状态", value: (r) => PB_STATUS_CSV(r.status) },
      { header: "健康", value: (r) => HEALTH[r.health][0] },
      { header: "循环次数", value: (r) => Math.round(r.cycles) },
      { header: "归档时间", value: (r) => r.archivedAt },
    ], rows as Powerbank[]),
  },
  monitor: {
    name: "实时监控",
    run: (rows) => exportCsv<CabinetMonitor>("实时监控", [
      { header: "柜机号", value: (r) => r.cabinetNo },
      { header: "点位", value: (r) => r.locationName },
      { header: "在线", value: (r) => (r.online ? "在线" : "离线") },
      { header: "最后心跳", value: (r) => r.heartbeatAt },
      { header: "信号(%)", value: (r) => Math.round(r.signal) },
      { header: "温度(°C)", value: (r) => Math.round(r.temp) },
      { header: "故障数", value: (r) => Math.round(r.faultCount) },
    ], rows as CabinetMonitor[]),
  },
  commands: {
    name: "远程指令记录",
    run: (rows) => exportCsv<CommandRecord>("远程指令记录", [
      { header: "指令ID", value: (r) => r.commandId },
      { header: "柜机号", value: (r) => r.cabinetNo },
      { header: "类型", value: (r) => CMD_TYPE[r.type] },
      { header: "仓位", value: (r) => r.slotIndex },
      { header: "状态", value: (r) => CMD_STATUS[r.status][0] },
      { header: "操作人", value: (r) => r.operator },
      { header: "时间", value: (r) => r.createdAt },
    ], rows as CommandRecord[]),
  },
  inventory: {
    name: "库存调拨",
    run: (rows) => exportCsv<InventoryTransfer>("库存调拨", [
      { header: "调拨单号", value: (r) => r.transferNo },
      { header: "调出点位", value: (r) => r.fromLocation },
      { header: "调入点位", value: (r) => r.toLocation },
      { header: "充电宝数", value: (r) => Math.round(r.powerbankCount) },
      { header: "状态", value: (r) => TRANSFER_STATUS[r.status][0] },
      { header: "操作人", value: (r) => r.operator },
      { header: "创建时间", value: (r) => r.createdAt },
    ], rows as InventoryTransfer[]),
  },
  ota: {
    name: "固件 OTA",
    run: (rows) => exportCsv<OtaRollout>("固件OTA", [
      { header: "发布单号", value: (r) => r.rolloutNo },
      { header: "固件版本", value: (r) => r.fwVersion },
      { header: "供应商", value: (r) => r.vendorCode },
      { header: "策略", value: (r) => (r.strategy === "FULL" ? "全量" : "灰度") },
      { header: "进度(%)", value: (r) => Math.round(r.progress) },
      { header: "状态", value: (r) => OTA_STATUS[r.status][0] },
      { header: "创建时间", value: (r) => r.createdAt },
    ], rows as OtaRollout[]),
  },
};

const SEARCH_HINT: Record<string, string> = {
  powerbanks: "搜索充电宝号 / 柜机号",
  monitor: "搜索柜机号 / 点位",
  commands: "搜索指令ID / 柜机号 / 操作人",
  inventory: "搜索调拨单号 / 点位",
  ota: "搜索发布单号 / 版本 / 供应商",
};

function DevicesInner() {
  const sp = useSearchParams();
  const qTab = sp.get("tab");
  const qc = useQueryClient();
  const allow = useCan();
  const [tab, setTab] = useState(TABS.some((t) => t.key === qTab) ? (qTab as string) : "cabinets");
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [pbForm, setPbForm] = useState<Partial<Powerbank> | null>(null);
  const [invForm, setInvForm] = useState<Partial<InventoryTransfer> | null>(null);
  const [otaForm, setOtaForm] = useState<Partial<OtaRollout> | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  // 监控 tab 的列表/地图切换（G4）。地图按**站点**聚合撒点，不逐台机柜——上千机柜会卡。
  const [monitorView, setMonitorView] = useState<"list" | "map">("list");
  const sitesQ = useQuery({
    queryKey: ["monitor-sites"],
    queryFn: () => api.listSites({ page: 1, size: 200 }),
    enabled: tab === "monitor" && monitorView === "map",
  });
  const mapPoints: MapPoint[] = useMemo(
    () => (sitesQ.data?.list ?? []).map((st) => ({
      id: st.siteNo,
      name: st.name,
      lat: st.lat,
      lng: st.lng,
      count: st.cabinetCount,
      desc: st.regionName,
      alert: st.status === "PAUSED", // 停用站点标红，一眼看出哪块不在服务
    })),
    [sitesQ.data],
  );
  const { confirm, dialog } = useConfirm();
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const isCabinets = tab === "cabinets";
  // 自建 tab 自带筛选/查询/分页，页面共用的 Toolbar 与 q 不参与
  const isStandalone = STANDALONE_TABS.includes(tab);
  const canEditCabinet = allow("device:cabinet:update");
  const canEditCode = canEditCabinet;
  const canEditPowerbank = allow("device:powerbank:update");
  const canEditInventory = allow("device:inventory:update");
  const canEditOta = allow("device:ota:publish");

  const invalidate = () => qc.invalidateQueries({ queryKey: ["devices"] });
  const savePb = useMutation({
    mutationFn: (v: Partial<Powerbank>) => api.savePowerbank(v),
    onSuccess: () => { invalidate(); notify.success("保存成功"); setPbForm(null); },
  });
  const saveInv = useMutation({
    mutationFn: (v: Partial<InventoryTransfer>) => api.saveInventoryTransfer(v),
    onSuccess: () => { invalidate(); notify.success("保存成功"); setInvForm(null); },
  });
  const saveOta = useMutation({
    mutationFn: (v: Partial<OtaRollout>) => api.saveOtaRollout(v),
    onSuccess: () => { invalidate(); notify.success("保存成功"); setOtaForm(null); },
  });

  const archivePb = useMutation({
    mutationFn: (no: string) => api.archivePowerbank(no),
    onSuccess: () => { invalidate(); notify.success("已归档"); },
  });
  const unarchivePb = useMutation({
    mutationFn: (no: string) => api.unarchivePowerbank(no),
    onSuccess: () => { invalidate(); notify.success("已恢复"); },
  });

  const q = useQuery<PageResult<Row>>({
    queryKey: ["devices", tab, page, keyword, showArchived],
    queryFn: () =>
      tab === "powerbanks" ? api.listPowerbanks({ page, size: SIZE, keyword, showArchived: showArchived || undefined })
      : tab === "monitor" ? api.listCabinetMonitor({ page, size: SIZE, keyword })
      : tab === "commands" ? api.listCommandRecords({ page, size: SIZE, keyword })
      : tab === "inventory" ? api.listInventoryTransfers({ page, size: SIZE, keyword })
      : api.listOtaRollouts({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: !isStandalone,
  });

  const editCell = (on: () => void, can: boolean) =>
    can ? <Button size="sm" variant="outline" onClick={on}>编辑</Button> : <span className="text-muted-foreground">-</span>;

  const pbColsFull: Column<Powerbank>[] = [
    ...pbCols,
    ...(showArchived ? [{ header: "归档时间", cell: (r: Powerbank) => <ArchivedAt at={r.archivedAt} /> }] : []),
    {
      header: "操作",
      cell: (r) => (
        <ArchiveActions
          archived={!!r.archivedAt}
          canWrite={canEditPowerbank}
          onArchive={async () => {
            // 充电宝不在「主数据强确认」清单里，故不要求手输编号
            if (await confirm(archiveConfirm("充电宝", r.powerbankNo))) archivePb.mutate(r.powerbankNo);
          }}
          onUnarchive={async () => {
            if (await confirm(unarchiveConfirm("充电宝", r.powerbankNo))) unarchivePb.mutate(r.powerbankNo);
          }}
          actions={<Button size="sm" variant="outline" onClick={() => setPbForm(r)}>编辑</Button>}
        />
      ),
    },
  ];
  const invColsFull: Column<InventoryTransfer>[] = [...invCols, { header: "操作", cell: (r) => editCell(() => setInvForm(r), canEditInventory) }];
  const otaColsFull: Column<OtaRollout>[] = [...otaCols, { header: "操作", cell: (r) => editCell(() => setOtaForm(r), canEditOta) }];

  return (
    <div>
      <TabHeader tabs={TABS} value={tab} onChange={(k) => { setTab(k); setPage(1); setKeyword(""); }} />

      {!isStandalone && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder={SEARCH_HINT[tab]}
          onAdd={
            tab === "powerbanks" && canEditPowerbank ? () => setPbForm({ cabinetNo: "", battery: 100, status: "IN_CABINET", health: "OK", cycles: 0 })
            : tab === "inventory" && canEditInventory ? () => setInvForm({ fromLocation: "", toLocation: "", powerbankCount: 1, status: "DRAFT", operator: "" })
            : tab === "ota" && canEditOta ? () => setOtaForm({ fwVersion: "", vendorCode: "", strategy: "GRAY", progress: 0, status: "PENDING" })
            : undefined
          }
          addLabel={tab === "powerbanks" ? "新增充电宝" : tab === "inventory" ? "新增调拨单" : tab === "ota" ? "新增发布单" : undefined}
          onExport={EXPORTS[tab] ? () => EXPORTS[tab].run(q.data?.list ?? []) : undefined}
        >
          {/* 监控 tab：列表 / 地图 双视图（G4）。地图按站点聚合，点 marker 回列表并带上站点筛选。 */}
          {tab === "monitor" && (
            <div className="flex gap-0.5 rounded-lg bg-secondary p-0.5" role="group" aria-label="监控视图">
              {(["list", "map"] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  aria-pressed={monitorView === v}
                  onClick={() => setMonitorView(v)}
                  className={cn(
                    "rounded-md px-2.5 py-1 text-sm transition-colors",
                    monitorView === v ? "bg-card font-medium text-foreground shadow-[var(--card-shadow)]" : "text-muted-foreground hover:text-foreground",
                  )}
                >{v === "list" ? "列表" : "地图"}</button>
              ))}
            </div>
          )}
          {/* 充电宝是可归档实体，故只有它需要「显示已归档」开关 */}
          {tab === "powerbanks" && (
            <ShowArchivedToggle
              checked={showArchived}
              onChange={(v) => { setShowArchived(v); setPage(1); }}
            />
          )}
        </Toolbar>
      )}

      {isCabinets && <CabinetsTab canWrite={canEditCabinet} />}
      {tab === "logs" && <LogsTab />}
      {tab === "codes" && <CodesTab canEdit={canEditCode} />}
      {tab === "powerbanks" && (
        <DataTable
          rowKey={(r: Powerbank) => r.powerbankNo}
          columns={pbColsFull}
          rows={q.data?.list as Powerbank[]}
          loading={q.isLoading}
          rowClassName={archivedRowClass}
          empty="暂无充电宝——新到货的充电宝需先入库建档；已归档的需勾选「显示已归档」才会出现"
        />
      )}
      {tab === "monitor" && (monitorView === "list"
        ? <DataTable rowKey={(r: CabinetMonitor) => r.cabinetNo} columns={monCols} rows={q.data?.list as CabinetMonitor[]} loading={q.isLoading} />
        : <SiteMap points={mapPoints} onSelect={(pt) => { setKeyword(pt.name); setMonitorView("list"); setPage(1); }} />)}
      {tab === "commands" && <DataTable rowKey={(r: CommandRecord) => r.commandId} columns={cmdCols} rows={q.data?.list as CommandRecord[]} loading={q.isLoading} />}
      {tab === "inventory" && <DataTable rowKey={(r: InventoryTransfer) => r.transferNo} columns={invColsFull} rows={q.data?.list as InventoryTransfer[]} loading={q.isLoading} />}
      {tab === "ota" && <DataTable rowKey={(r: OtaRollout) => r.rolloutNo} columns={otaColsFull} rows={q.data?.list as OtaRollout[]} loading={q.isLoading} />}
      {!isStandalone && q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}
      {dialog}

      <FormDrawer
        open={!!pbForm}
        onOpenChange={(o) => !o && setPbForm(null)}
        titleNew="新增充电宝"
        titleEdit={`编辑充电宝 ${pbForm?.powerbankNo ?? ""}`}
        isEdit={!!pbForm?.powerbankNo}
        fields={PB_FIELDS}
        value={(pbForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setPbForm(v as Partial<Powerbank>)}
        onSubmit={() => pbForm && savePb.mutate(pbForm)}
        submitting={savePb.isPending}
      />
      <FormDrawer
        open={!!invForm}
        onOpenChange={(o) => !o && setInvForm(null)}
        titleNew="新增调拨单"
        titleEdit={`编辑调拨单 ${invForm?.transferNo ?? ""}`}
        isEdit={!!invForm?.transferNo}
        fields={INV_FIELDS}
        value={(invForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setInvForm(v as Partial<InventoryTransfer>)}
        onSubmit={() => invForm && saveInv.mutate(invForm)}
        submitting={saveInv.isPending}
      />
      <FormDrawer
        open={!!otaForm}
        onOpenChange={(o) => !o && setOtaForm(null)}
        titleNew="新增发布单"
        titleEdit={`编辑发布单 ${otaForm?.rolloutNo ?? ""}`}
        isEdit={!!otaForm?.rolloutNo}
        fields={OTA_FIELDS}
        value={(otaForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setOtaForm(v as Partial<OtaRollout>)}
        onSubmit={() => otaForm && saveOta.mutate(otaForm)}
        submitting={saveOta.isPending}
      />
    </div>
  );
}

export default function DevicesPage() {
  return <Suspense fallback={null}><DevicesInner /></Suspense>;
}
