"use client";

import { Suspense, useEffect, useState, useMemo, type ReactNode } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { UNPAGED_SIZE } from "@/lib/constants";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { usePaging } from "@/lib/hooks/use-paging";
import { useNavTabs, usePageTab, keepWithinTab } from "@/lib/hooks/use-page-tab";
import { Select } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Progress } from "@/components/ui/progress";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { StatusBadge, statusOptions, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { segmentedTrackClass, segmentedItemClass } from "@/components/ui/segmented";
import { Button } from "@/components/ui/button";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { Drawer } from "@/components/ui/drawer";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ShowArchivedToggle, ArchiveActions, ArchivedAt, archivedRowClass,
  archiveConfirm, unarchiveConfirm,
} from "@/components/archive";
import { fmtTime, cn } from "@/lib/utils";
import { useCan } from "@/lib/hooks/use-can";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
import { parseImport, templateCsv, type ImportColumn, type RowError } from "@/lib/import-csv";
import { SiteMap, type MapPoint } from "@/components/ui/site-map";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 值导入：指令的「必须指定仓位」规则与 mock 落库校验同源，不在页面里另写一份
import { SLOT_REQUIRED_COMMANDS } from "@/lib/types";
import type {
  Cabinet, Powerbank, CabinetMonitor, CommandRecord, CommandType, InventoryTransfer,
  InventoryTransferDetail, TransferItem, OtaRollout, PageResult,
  OtaRelease, OtaTask, DeviceLog, DeviceCodeBatch,
} from "@/lib/types";

// tab 只声明有哪些、什么顺序；名字与权限来自 nav.ts（见 navTabs）。
// 「机柜」在菜单里叫「设备台账」、「远程指令记录」叫「远程控制·指令记录」——以菜单为准。
const TAB_KEYS = ["cabinets", "powerbanks", "monitor", "commands", "logs",
  "inventory", "codes", "ota"] as const;
// 自建 tab（自带筛选器与查询），不走页面共用的 keyword/Toolbar/分页那一套
const STANDALONE_TABS = ["cabinets", "logs", "codes"];

/**
 * 同一张 tab 内的视图切换段控（监控的「列表/地图」、固件 OTA 的「投放/版本库」）。
 * 原先只有监控就地手搓了一份，OTA 是第二处——抽出来免得继续复制粘贴。
 */
function ViewSwitch<T extends string>({ label, value, options, onChange }: {
  label: string;
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    // 形状不自己拼：与 Tabs / TabHeader 共用 segmented 那份规格，
    // 否则同一行里（搜索框 · 筛选下拉 · 段控）会出现三种圆角
    <div className={segmentedTrackClass()} role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={segmentedItemClass(value === o.value, "px-2.5 py-1 text-sm")}
        >{o.label}</button>
      ))}
    </div>
  );
}

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
  const pointsQ = useQuery({ queryKey: ["locations", "all"], queryFn: () => api.listLocations({ size: UNPAGED_SIZE }) });
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
          <div className="rounded-card bg-muted p-4">
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
              className="block w-full cursor-pointer rounded-card bg-secondary p-2.5 text-sm file:me-3 file:cursor-pointer file:rounded-field file:border-0 file:bg-primary file:px-3 file:py-1.5 file:text-primary-foreground"
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
            <div className="rounded-card bg-destructive/10 p-4">
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
            <div className="rounded-card bg-muted p-3">校验全部通过，可以导入。</div>
          )}

          {rows.length > 0 && (
            <div>
              <div className="mb-1.5 text-xs text-muted-foreground">
                预览前 10 行（共 {rows.length} 条）
              </div>
              <div className="overflow-x-auto rounded-card bg-muted/60">
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

/**
 * 单柜指令词表 = 批量那三条 + 两条只能单柜下发的（弹仓/锁仓都要指定仓位，
 * 批量下发说不清对哪个仓）。词表本身沿用 types 层的 `COMMAND_TYPES`，这里只加中文与提示。
 */
const SINGLE_COMMANDS: { value: string; label: string; hint: string }[] = [
  { value: "EJECT", label: "弹出充电宝", hint: "仓位留空 = 弹任意可弹仓；填了只弹该仓" },
  { value: "LOCK", label: "锁仓", hint: "锁住指定仓位：故障仓先锁再报修，避免继续借出" },
  ...BATCH_COMMANDS,
];
/** 这两条才用得上仓位号，其余指令的仓位输入直接禁用（不提交隐藏字段的脏数据）。 */
const SLOT_COMMANDS = ["EJECT", "LOCK"];

// —— 机柜建档抽屉（S8）——
// 导入是「一次上百台」的入口，建档是「到货一台、补一台」的入口，两者都得有：
// 原先只有导入，加一台机柜得先编一个 CSV。
//
// **归属站点 / 站点名 / 归属代理 / 在线态 / 心跳一律不在表单里**：
// 前三个是派生或另有唯一写入口（点位反查 / 代理划拨），后两个是设备上报的事实——
// 表单里填「在线」不会让机器真的在线，只会让台账骗人。校验同在 mock 层（`saveCabinet`），
// 抽屉这层只做即时提示，换个入口塞不进脏数据。
const cabinetFields = (
  vendorCodes: string[],
  points: { locationNo: string; name: string }[],
  siteHint: string,
): FieldDef[] => [
  { key: "cabinetNo", label: "柜机号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "设备信息",
    help: "手填需形如 CAB2000（与导入模板同一条规则）" },
  { key: "sn", label: "出厂序列号 SN", required: true, maxLength: 32, placeholder: "SN95000", section: "设备信息",
    help: "现场核机与厂商保修的唯一凭据，全平台不得重复" },
  { key: "vendorCode", label: "供应商", type: "select", required: true, section: "设备信息",
    options: vendorCodes.map((v) => ({ value: v, label: v })) },
  { key: "model", label: "型号", required: true, maxLength: 24, placeholder: "X6", section: "设备信息" },
  { key: "slotTotal", label: "仓位数", type: "number", required: true, min: 1, max: 48, section: "设备信息",
    help: "1~48；编辑时不得小于当前在仓充电宝数" },
  { key: "fwVersion", label: "出厂固件版本", placeholder: "1.4.0", section: "设备信息",
    help: "留空按出厂默认 1.0.0 记账；真实版本以设备首次心跳上报为准" },
  // 点位是唯一的「归属入口」：站点号与站点名都从它反查，所以这里给的是选择而非输入
  { key: "locationNo", label: "点位", type: "select", section: "上架归属",
    options: [{ value: "", label: "未上架（到货待部署）" }, ...points.map((p) => ({ value: p.locationNo, label: `${p.locationNo} · ${p.name}` }))],
    help: `归属站点由点位反查，不单独维护 —— 当前：${siteHint}` },
  { key: "status", label: "设备状态", type: "select", required: true, section: "上架归属", options: [
    // 在库（到货未投放）也要能选：编辑一台仓库里的柜子时，少了这一档会让
    // 必填的状态框显示空值，逼着运营把它改成「在用」——那就把它错误地投放了
    { value: "IN_STOCK", label: "在库" },
    { value: "DEPLOYED", label: "在用" }, { value: "FAULT", label: "故障" }, { value: "RETIRED", label: "报废" },
  ] },
];

function CabinetsTab({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [online, setOnline] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [cmdType, setCmdType] = useState("REBOOT");
  const [importOpen, setImportOpen] = useState(false);
  const [cabForm, setCabForm] = useState<Partial<Cabinet> | null>(null);

  // 建档抽屉的两份主数据（供应商 / 点位）。查询键与导入抽屉一致，两处共用同一份缓存
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  const pointsQ = useQuery({ queryKey: ["locations", "all"], queryFn: () => api.listLocations({ size: UNPAGED_SIZE }) });

  // 换页/改筛选后选中的行已不在视野内，继续留着会造成「对看不见的行动手」
  const resetPage = (fn: () => void) => { fn(); paging.reset(); setSelected([]); };

  const { data, isLoading } = useQuery({
    queryKey: ["cabinets", paging.page, paging.size, keyword, online, status, showArchived],
    queryFn: () => api.listCabinets({
      page: paging.page, size: paging.size, keyword,
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
    // 下发即在指令记录留痕，故连指令记录的查询一并失效（同一页另一张 tab 就在看它）
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ["devices"] }); setSelected([]); notify.success(`已向 ${r.length} 台机柜下发指令`); },
  });
  const saveCab = useMutation({
    mutationFn: (v: Partial<Cabinet>) => api.saveCabinet(v),
    onSuccess: (c) => { invalidate(); notify.success(`已保存机柜 ${c.cabinetNo}`); setCabForm(null); },
  });

  // 只提交建档字段：把整行回传会连 availableCount / 心跳一起发过去，
  // 服务端虽然不采信，但请求体里出现「表单改得了在线态」的假象本身就是误导
  const submitCab = () => {
    if (!cabForm) return;
    const { cabinetNo, sn, vendorCode, model, slotTotal, locationNo, fwVersion, status } = cabForm;
    saveCab.mutate({ cabinetNo, sn, vendorCode, model, slotTotal, locationNo: locationNo || null, fwVersion, status });
  };

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
    // 归属站点（偏差 A1）：站点号 + 点位名同列，便于与站点坪效/门店生命周期对号；
    // 未上架（无点位）或后端尚未提供该列时一律显示「未归属」，不猜
    { header: "归属站点", cell: (c) => <span className="text-muted-foreground tabular-nums">{c.siteNo ?? "未归属"}</span> },
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
            <>
              <Link className="text-primary hover:underline" href={`/devices/detail?no=${c.cabinetNo}`}>详情</Link>
              {canWrite && <Button size="sm" variant="outline" onClick={() => setCabForm(c)}>编辑</Button>}
            </>
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
        searchPlaceholder="搜索柜机号 / 点位 / 站点号"
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
        // 主按钮给「新增机柜」（到货补一台是日常动作），导入退到筛选区——
        // 为了加一台机柜先编一个 CSV 是原先最别扭的一处
        onAdd={canWrite ? () => setCabForm({ vendorCode: vendorsQ.data?.[0]?.vendorCode ?? "cd-tech", model: "", sn: "", slotTotal: 8, locationNo: "", fwVersion: "", status: "DEPLOYED" }) : undefined}
        addLabel="新增机柜"
        onExport={() => exportCsv<Cabinet>("机柜台账", [
          { header: "柜机号", value: (c) => c.cabinetNo },
          { header: "SN", value: (c) => c.sn },
          { header: "点位", value: (c) => c.locationName },
          { header: "点位编号", value: (c) => c.locationNo },
          { header: "归属站点", value: (c) => c.siteNo },
          { header: "供应商", value: (c) => c.vendorCode },
          { header: "型号", value: (c) => c.model },
          { header: "可借", value: (c) => c.availableCount },
          { header: "仓位数", value: (c) => c.slotTotal },
          { header: "在线", value: (c) => (c.onlineStatus === "ONLINE" ? "在线" : "离线") },
          { header: "状态", value: (c) => ({ IN_STOCK: "在库", DEPLOYED: "在用", FAULT: "故障", RETIRED: "报废" })[c.status] },
          { header: "固件", value: (c) => c.fwVersion },
          { header: "最后心跳", value: (c) => c.lastHeartbeatAt },
          { header: "归档时间", value: (c) => c.archivedAt },
        ], rows)}
      >
        <FilterSelect
          value={online}
          onChange={(v) => resetPage(() => setOnline(v))}
          options={[{ value: "ONLINE", label: "在线" }, { value: "OFFLINE", label: "离线" }]}
          allLabel="全部在线态"
          aria-label="按在线态筛选"
        />
        <FilterSelect
          value={status}
          onChange={(v) => resetPage(() => setStatus(v))}
          options={[{ value: "IN_STOCK", label: "在库" }, { value: "DEPLOYED", label: "在用" }, { value: "FAULT", label: "故障" }, { value: "RETIRED", label: "报废" }]}
          allLabel="全部状态"
          aria-label="按机柜状态筛选"
        />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => resetPage(() => setShowArchived(v))} />
        {canWrite && <Button size="sm" variant="outline" onClick={() => setImportOpen(true)}>导入台账</Button>}
      </Toolbar>

      {!canWrite && (
        <ReadOnlyNotice what="机柜维护" perm="device:cabinet:update" note="建档、编辑、归档与导入不可用" />
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
        empty="暂无机柜——可用右上角「新增机柜」逐台建档、或「导入台账」批量建档，也确认一下筛选条件是否过窄（已归档的机柜需勾选「显示已归档」才会出现）"
      />
      {data && <Pagination page={paging.page} size={paging.size} total={data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      <FormDrawer
        open={!!cabForm}
        onOpenChange={(o) => !o && setCabForm(null)}
        titleNew="新增机柜（建档）"
        titleEdit={`编辑机柜 ${cabForm?.cabinetNo ?? ""}`}
        isEdit={!!cabForm?.cabinetNo}
        fields={cabinetFields(
          (vendorsQ.data ?? []).map((v) => v.vendorCode),
          (pointsQ.data?.list ?? []).map((l) => ({ locationNo: l.locationNo, name: l.name })),
          // 站点名随选中的点位实时变，让「填点位 = 定归属」这件事在抽屉里就看得见
          (pointsQ.data?.list ?? []).find((l) => l.locationNo === cabForm?.locationNo)?.siteName ?? "未归属（未上架）",
        )}
        value={(cabForm ?? {}) as Record<string, unknown>}
        onChange={(v) => setCabForm(v as Partial<Cabinet>)}
        onSubmit={submitCab}
        submitting={saveCab.isPending}
      />
      <ImportCabinetsDrawer open={importOpen} onOpenChange={setImportOpen} />
      {dialog}
    </div>
  );
}

// —— 设备日志（规格 §1）：双流合一（COMMAND 下发 / REPORT 上报）——
// 竞品只有设备上报；两条流放同一时间轴后，「下发了什么 → 设备回了什么」的因果一眼可见。
const LOG_STREAM: StatusMap<DeviceLog["stream"]> = {
  COMMAND: { label: "↓ 下发", tone: "default" }, // primary 色标
  REPORT: { label: "↑ 上报", tone: "muted" },
};
const LOG_RESULT: StatusMap<DeviceLog["result"]> = {
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
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [stream, setStream] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const reset = () => paging.reset();

  const { data, isLoading } = useQuery({
    queryKey: ["device-logs", paging.page, paging.size, keyword, stream, from, to],
    queryFn: () => api.listDeviceLogs({ page: paging.page, size: paging.size, keyword, stream: stream || undefined, from: from || undefined, to: to || undefined }),
    placeholderData: keepPreviousData,
  });

  const cols: Column<DeviceLog>[] = [
    { header: "日志号", cell: (l) => <span className="txt-strong tabular-nums">{l.logNo}</span> },
    // 机柜号是这张表的第二个扫描锚点：排障时先按柜子聚合再看时间轴
    { header: "机柜号", cell: (l) => <span className="txt-strong tabular-nums">{l.cabinetNo}</span> },
    { header: "流向", cell: (l) => <StatusBadge map={LOG_STREAM} value={l.stream} /> },
    { header: "事件类型", cell: (l) => <Badge tone="outline">{LOG_EVENT[l.eventType] ?? l.eventType}</Badge> },
    // 报文行内截断；完整内容点行首箭头展开（DataTable expandable）
    { header: "报文", cell: (l) => <span className="block max-w-[20rem] truncate font-mono text-xs text-muted-foreground">{l.payload}</span> },
    { header: "厂商", cell: (l) => <span className="text-muted-foreground">{l.vendorCode}</span> },
    { header: "时间", cell: (l) => <span className="text-muted-foreground">{fmtTime(l.occurredAt)}</span> },
    { header: "结果", cell: (l) => <StatusBadge map={LOG_RESULT} value={l.result} /> },
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
        <FilterSelect
          value={stream}
          onChange={(v) => { setStream(v); reset(); }}
          options={[{ value: "COMMAND", label: "指令下发" }, { value: "REPORT", label: "设备上报" }]}
          allLabel="全部双流"
          aria-label="按双流方向筛选"
        />
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
            <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-card bg-card p-3 font-mono text-xs">{l.payload}</pre>
          </div>
        )}
      />
      {data && <Pagination page={paging.page} size={paging.size} total={data.total} onPage={paging.setPage} onSize={paging.setSize} />}
    </div>
  );
}

// —— 设备编码（规格 §2）：按批次 + 供应商归集，跟踪贴码绑定进度 ——
const CODE_STATUS: StatusMap<DeviceCodeBatch["status"]> = {
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
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [form, setForm] = useState<Partial<DeviceCodeBatch> | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["device-code-batches", paging.page, paging.size, keyword],
    queryFn: () => api.listDeviceCodeBatches({ page: paging.page, size: paging.size, keyword }),
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
    { header: "状态", cell: (b) => <StatusBadge map={CODE_STATUS} value={b.status} /> },
    { header: "操作", cell: (b) => canEdit ? <Button size="sm" variant="outline" onClick={() => setForm({ ...b, producedAt: b.producedAt.slice(0, 10) })}>编辑</Button> : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <div>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
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
      {/* 编码批次与机柜维护共用一个权限码：贴码区间是机柜台账的上游主数据 */}
      {!canEdit && <ReadOnlyNotice what="设备编码维护" perm="device:cabinet:update" note="不能新建或纠偏贴码批次" />}
      <DataTable
        rowKey={(b: DeviceCodeBatch) => b.batchNo}
        columns={cols}
        rows={data?.list}
        loading={isLoading}
        empty="暂无贴码批次——设备到货后先在此登记编码区间，机柜建档时才能按区间校验编码归属"
      />
      {data && <Pagination page={paging.page} size={paging.size} total={data.total} onPage={paging.setPage} onSize={paging.setSize} />}

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
const PB_STATUS: StatusMap<Powerbank["status"]> = {
  IN_CABINET: { label: "在仓", tone: "success" },
  RENTED: { label: "借出中", tone: "warning" },
  FAULT: { label: "故障", tone: "danger" },
  RETIRED: { label: "报废", tone: "muted" },
};
const HEALTH: StatusMap<"OK" | "FAULT"> = {
  OK: { label: "正常", tone: "success" },
  FAULT: { label: "故障", tone: "danger" },
};
const CMD_TYPE: Record<CommandRecord["type"], string> = {
  EJECT: "弹出", LOCK: "锁仓", REBOOT: "重启", LOCATE: "定位", FW_SYNC: "同步固件版本",
};
const CMD_STATUS: StatusMap<CommandRecord["status"]> = {
  SENT: { label: "已下发", tone: "warning" },
  ACKED: { label: "已确认", tone: "success" },
  TIMEOUT: { label: "超时", tone: "muted" },
  FAILED: { label: "失败", tone: "danger" },
};
const TRANSFER_STATUS: StatusMap<InventoryTransfer["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  IN_TRANSIT: { label: "在途", tone: "warning" },
  DONE: { label: "已完成", tone: "success" },
};
const ITEM_CHECKED: StatusMap<"CHECKED" | "UNCHECKED"> = {
  CHECKED: { label: "已核对", tone: "success" },
  UNCHECKED: { label: "待核对", tone: "warning" },
};
const OTA_STATUS: StatusMap<OtaRollout["status"]> = {
  PENDING: { label: "待发布", tone: "muted" },
  RUNNING: { label: "升级中", tone: "warning" },
  DONE: { label: "已完成", tone: "success" },
  ROLLBACK: { label: "已回滚", tone: "danger" },
};

// —— 各扩展 tab 列定义 ——
/**
 * 充电宝列。**做成函数**是为了把厂商编码翻成厂商名 —— 解析要用 vendorsQ，
 * 那是组件内的数据，模块级常量取不到。本组列只有 pbColsFull 一个调用方。
 */
const pbColsOf = (vendorName: (code: string | null) => string): Column<Powerbank>[] => [
  {
    header: "充电宝号",
    cell: (r) => (
      <>
        <span className="font-medium">{r.powerbankNo}</span>
        {/* sn 跟在下面：退换货、保修、跟厂商对故障都只认它 */}
        {r.sn && <div className="truncate txt-caption text-muted-foreground">{r.sn}</div>}
      </>
    ),
  },
  {
    header: "供应商",
    // 混合硬件接入：同一批故障集中在某个厂商上是第一个要看的信号
    cell: (r) => <span className="text-muted-foreground">{vendorName(r.vendorCode)}</span>,
  },
  {
    header: "所属柜机",
    cell: (r) => (
      <>
        {r.cabinetNo}
        {/* 借出中的不在柜子里，仓位为 null —— 显示「借出中」而不是空白，
            空白读起来像数据缺失，而这是这块电的真实状态 */}
        <div className="truncate txt-caption text-muted-foreground">
          {r.slotIndex === null ? "借出中" : `${r.slotIndex} 号仓`}
        </div>
      </>
    ),
  },
  { header: "电量", cell: (r) => <span className="tabular-nums">{Math.round(r.battery)}%</span> },
  { header: "状态", cell: (r) => <StatusBadge map={PB_STATUS} value={r.status} /> },
  { header: "健康", cell: (r) => <StatusBadge map={HEALTH} value={r.health} /> },
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
/**
 * 下发 → 设备确认的往返耗时。**排障第一个要问的数**，而只有 createdAt 时答不出来
 * （创建、下发、确认是三个时刻，此前界面只有第一个）。
 * 未确认一律返回 null 而不是 0 —— 0 会被读成「秒确认」。
 */
const ackCost = (r: CommandRecord) => {
  if (!r.sentAt || !r.confirmedAt) return null;
  const ms = Date.parse(r.confirmedAt) - Date.parse(r.sentAt);
  if (!Number.isFinite(ms) || ms < 0) return null;
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
};

const cmdCols: Column<CommandRecord>[] = [
  { header: "指令ID", cell: (r) => <span className="font-medium">{r.commandId}</span> },
  {
    header: "柜机号",
    cell: (r) => (
      <>
        {r.cabinetNo}
        {/* sn 跟在下面：跟厂商对日志时对方只认 sn，只有柜机号就得先回查一次映射 */}
        {r.sn && <div className="truncate txt-caption text-muted-foreground">{r.sn}</div>}
      </>
    ),
  },
  { header: "类型", cell: (r) => <Badge tone="outline">{CMD_TYPE[r.type]}</Badge> },
  { header: "仓位", cell: (r) => <span className="tabular-nums">{r.slotIndex ?? "-"}</span> },
  {
    header: "状态",
    cell: (r) => (
      <>
        <StatusBadge map={CMD_STATUS} value={r.status} />
        {/* **只在 >1 时显示**：给每行挂「重试 1 次」是纯噪音，而「重试 4 次」正是要看的。
            一次成功与重试三次才成功都是 ACKED，只看状态永远看不出后者。 */}
        {(r.retry ?? 1) > 1 && <Badge tone="warning" className="ml-1">重试 {r.retry}</Badge>}
      </>
    ),
  },
  {
    header: "关联订单",
    cell: (r) => (r.orderNo
      ? <span className="tabular-nums">{r.orderNo}</span>
      /* 运维手动下发的没有订单。用「手动」而不是「-」：后者读起来像数据缺失 */
      : <span className="text-muted-foreground">手动</span>),
  },
  {
    header: "确认耗时",
    className: "text-right",
    cell: (r) => {
      const c = ackCost(r);
      return c
        ? <span className="tabular-nums">{c}</span>
        : <span className="text-muted-foreground">未确认</span>;
    },
  },
  { header: "操作人", cell: (r) => <span className="text-muted-foreground">{r.operator}</span> },
  { header: "时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
];
/**
 * 调拨列表只说得出「从哪到哪、多少台」。**盘点对不上时要查的是「具体哪几台」**，
 * 那一层在详情里（列表的出参类型根本没有明细行）。故单号做成详情入口。
 */
function invColsWith(onOpen: (r: InventoryTransfer) => void): Column<InventoryTransfer>[] {
  return [
  { header: "调拨单号", cell: (r) => (
    <button type="button" className="font-medium tabular-nums underline-offset-4 hover:underline"
      onClick={() => onOpen(r)}>{r.transferNo}</button>
  ) },
  // 名字是给人看的，编号才说得清「到底去了哪个站点/仓库」
  { header: "调出", cell: (r) => (
    <div className="min-w-0">
      <div className="truncate">{r.fromLocation}</div>
      {r.fromRef ? <div className="truncate txt-caption text-muted-foreground tabular-nums">{r.fromRef}</div> : null}
    </div>
  ) },
  { header: "调入", cell: (r) => (
    <div className="min-w-0">
      <div className="truncate">{r.toLocation}</div>
      {r.toRef ? <div className="truncate txt-caption text-muted-foreground tabular-nums">{r.toRef}</div> : null}
    </div>
  ) },
  { header: "充电宝数", cell: (r) => <span className="tabular-nums">{Math.round(r.powerbankCount)}</span> },
  { header: "状态", cell: (r) => <StatusBadge map={TRANSFER_STATUS} value={r.status} /> },
  { header: "操作人", cell: (r) => <span className="text-muted-foreground">{r.operator}</span> },
  { header: "创建时间", cell: (r) => <span className="text-muted-foreground">{fmtTime(r.createdAt)}</span> },
  ];
}
const otaCols: Column<OtaRollout>[] = [
  { header: "发布单号", cell: (r) => <span className="font-medium">{r.rolloutNo}</span> },
  { header: "固件版本", cell: (r) => r.fwVersion },
  { header: "供应商", cell: (r) => r.vendorCode },
  { header: "策略", cell: (r) => <Badge tone="outline">{r.strategy === "FULL" ? "全量" : "灰度"}</Badge> },
  { header: "进度", cell: (r) => <span className="tabular-nums">{Math.round(r.progress)}%</span> },
  { header: "状态", cell: (r) => <StatusBadge map={OTA_STATUS} value={r.status} /> },
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
const PB_STATUS_CSV = (s: Powerbank["status"]) => PB_STATUS[s].label;
const EXPORTS: Record<string, { name: string; run: (rows: Row[]) => void }> = {
  powerbanks: {
    name: "充电宝管理",
    run: (rows) => exportCsv<Powerbank>("充电宝管理", [
      { header: "充电宝号", value: (r) => r.powerbankNo },
      // 导出跟着表格补：盘点/退换货拿这份去对，缺的恰好是厂商唯一认的 sn
      { header: "设备SN", value: (r) => r.sn },
      { header: "供应商", value: (r) => r.vendorCode },
      { header: "所属柜机", value: (r) => r.cabinetNo },
      { header: "仓位", value: (r) => r.slotIndex },
      { header: "电量(%)", value: (r) => Math.round(r.battery) },
      { header: "状态", value: (r) => PB_STATUS_CSV(r.status) },
      { header: "健康", value: (r) => HEALTH[r.health].label },
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
      // 导出跟着表格一起补：表上有、导出没有的话，拿导出去跟厂商对日志时
      // 恰好缺的就是对方唯一认的那列（sn）与「发了几次」。
      { header: "设备SN", value: (r) => r.sn },
      { header: "类型", value: (r) => CMD_TYPE[r.type] },
      { header: "仓位", value: (r) => r.slotIndex },
      { header: "状态", value: (r) => CMD_STATUS[r.status].label },
      { header: "重试次数", value: (r) => r.retry },
      { header: "关联订单", value: (r) => r.orderNo },
      { header: "操作人", value: (r) => r.operator },
      { header: "下发时间", value: (r) => r.sentAt },
      { header: "确认时间", value: (r) => r.confirmedAt },
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
      { header: "状态", value: (r) => TRANSFER_STATUS[r.status].label },
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
      { header: "状态", value: (r) => OTA_STATUS[r.status].label },
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

// ============================================================================
// 固件 OTA 补齐：版本库（投放引用的货架）+ 逐设备任务明细（投放下钻）
// 原先只有「投放」一张表：投的是哪个包、校验和多少、是否强制升级无处可看，
// 进度卡住也定位不到具体哪台柜子。两个后端端点早已就绪，前端一直没入口。
// ============================================================================
const RELEASE_STATUS: StatusMap<OtaRelease["status"]> = {
  DRAFT: { label: "草稿", tone: "muted" },
  PUBLISHED: { label: "已发布", tone: "success" },
  PAUSED: { label: "已暂停", tone: "warning" },
  COMPLETED: { label: "已完结", tone: "outline" },
};
// 固件类型是后端自由文本（厂商随时会上报新类型）：已知才翻译，未知原样显示，不能因此空白
const FW_TYPE: Record<string, string> = { MCU: "主控", SLOT: "仓门", MODEM: "通信模组" };
const TASK_STATUS: StatusMap<OtaTask["status"]> = {
  PENDING: { label: "排队中", tone: "muted" },
  DOWNLOADING: { label: "下载中", tone: "warning" },
  DOWNLOADED: { label: "待安装", tone: "warning" },
  INSTALLING: { label: "安装中", tone: "warning" },
  SUCCESS: { label: "成功", tone: "success" },
  FAILED: { label: "失败", tone: "danger" },
  ROLLED_BACK: { label: "已回滚", tone: "danger" },
};

const VENDOR_OPTIONS = [
  { value: "cd-tech", label: "cd-tech" }, { value: "sd-power", label: "sd-power" }, { value: "chargenow", label: "chargenow" },
];
const RELEASE_FIELDS: FieldDef[] = [
  { key: "releaseNo", label: "版本单号", readOnlyOnEdit: true, placeholder: "留空自动生成", section: "固件信息" },
  { key: "fwType", label: "固件类型", type: "select", required: true, section: "固件信息", options: [
    { value: "MCU", label: "主控 MCU" }, { value: "SLOT", label: "仓门 SLOT" }, { value: "MODEM", label: "通信模组 MODEM" },
  ] },
  { key: "vendorCode", label: "适用供应商", type: "select", section: "固件信息",
    options: [{ value: "", label: "通用（不限供应商）" }, ...VENDOR_OPTIONS],
    help: "留空 = 通用固件，任何厂商的投放都能引用；填了则只有该厂商设备可投" },
  { key: "version", label: "固件版本号", required: true, placeholder: "1.5.0", section: "固件信息" },
  { key: "versionCode", label: "版本序号", type: "number", required: true, min: 1, section: "固件信息",
    help: "设备靠它比大小判断能否升级，必须比同类型历史版本大（如 1.5.0 → 150）" },
  { key: "artifactUrl", label: "固件包地址", required: true, placeholder: "https://…/mcu-1.5.0.bin", section: "固件包" },
  { key: "checksum", label: "校验和", required: true, placeholder: "sha256:…", section: "固件包",
    help: "设备下载后自校验；留空等于让现场设备装一个未经校验的包" },
  { key: "mandatory", label: "强制升级", type: "switch", section: "固件包" },
  { key: "status", label: "发布状态", type: "select", required: true, section: "固件包", options: [
    { value: "DRAFT", label: "草稿" }, { value: "PUBLISHED", label: "已发布" },
    { value: "PAUSED", label: "已暂停" }, { value: "COMPLETED", label: "已完结" },
  ] },
  { key: "releaseNotes", label: "版本说明", type: "textarea", rows: 3, section: "固件包" },
];

function OtaReleasesTab({ canManage, viewSwitch }: { canManage: boolean; viewSwitch: ReactNode }) {
  const qc = useQueryClient();
  const paging = usePaging();
  const [keyword, setKeyword] = useState("");
  const [fwType, setFwType] = useState("");
  const [status, setStatus] = useState("");
  const [form, setForm] = useState<Partial<OtaRelease> | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["ota-releases", paging.page, paging.size, keyword, fwType, status],
    queryFn: () => api.listOtaReleases({ page: paging.page, size: paging.size, keyword, fwType: fwType || undefined, status: status || undefined }),
    placeholderData: keepPreviousData,
  });
  const save = useMutation({
    mutationFn: (v: Partial<OtaRelease>) => api.saveOtaRelease(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["ota-releases"] }); notify.success("已建版本"); setForm(null); },
  });

  // FieldDef 表达不了「格式 + 同类型不得重号」这类约束，提交前补一道
  const submit = () => {
    if (!form) return;
    if (!/^\d+\.\d+\.\d+$/.test(form.version ?? "")) { notify.error("固件版本号需形如 1.5.0"); return; }
    // 只能拿当前页兜一层（服务端有唯一键兜底）：同类型同版本号重复会让设备端无法判断该装哪个包
    const dup = (data?.list ?? []).some((r) => r.fwType === form.fwType && r.version === form.version && r.releaseNo !== form.releaseNo);
    if (dup) { notify.error("该固件类型下版本号已存在"); return; }
    // 供应商留空即「通用固件」，落 null 而不是空串——后端以 null 表示不限供应商
    save.mutate({ ...form, vendorCode: form.vendorCode || null });
  };

  const cols: Column<OtaRelease>[] = [
    { header: "版本单号", cell: (r) => <span className="font-medium tabular-nums">{r.releaseNo}</span> },
    { header: "固件类型", cell: (r) => <Badge tone="outline">{FW_TYPE[r.fwType] ?? r.fwType}</Badge> },
    { header: "适用供应商", cell: (r) => r.vendorCode ?? <span className="text-muted-foreground">通用</span> },
    // 版本号与序号并排：序号是设备判断能否升级的依据，只看版本号看不出先后
    { header: "版本", cell: (r) => <span className="tabular-nums">{r.version} <span className="text-muted-foreground">#{r.versionCode}</span></span> },
    { header: "强制升级", cell: (r) => r.mandatory ? <Badge tone="danger">强制</Badge> : <span className="text-muted-foreground">可选</span> },
    { header: "状态", cell: (r) => <StatusBadge map={RELEASE_STATUS} value={r.status} /> },
    // 校验和等宽显示：现场核包时要逐位对
    { header: "固件包", cell: (r) => (
      <div className="max-w-[18rem]">
        <div className="truncate font-mono text-xs">{r.artifactUrl}</div>
        <div className="truncate font-mono text-xs text-muted-foreground">{r.checksum}</div>
      </div>
    ) },
    { header: "版本说明", cell: (r) => <span className="block max-w-[16rem] truncate text-muted-foreground">{r.releaseNotes}</span> },
  ];

  return (
    <div>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); paging.reset(); }}
        searchPlaceholder="搜索版本单号 / 版本号 / 版本说明"
        onAdd={canManage ? () => setForm({ fwType: "MCU", vendorCode: "", version: "", versionCode: 100, artifactUrl: "", checksum: "", mandatory: false, status: "DRAFT", releaseNotes: "" }) : undefined}
        addLabel="新建版本"
        onExport={() => exportCsv<OtaRelease>("固件版本库", [
          { header: "版本单号", value: (r) => r.releaseNo },
          { header: "固件类型", value: (r) => r.fwType },
          { header: "适用供应商", value: (r) => r.vendorCode ?? "通用" },
          { header: "版本号", value: (r) => r.version },
          { header: "版本序号", value: (r) => r.versionCode },
          { header: "强制升级", value: (r) => (r.mandatory ? "强制" : "可选") },
          { header: "状态", value: (r) => RELEASE_STATUS[r.status].label },
          { header: "固件包地址", value: (r) => r.artifactUrl },
          { header: "校验和", value: (r) => r.checksum },
          { header: "版本说明", value: (r) => r.releaseNotes },
        ], data?.list ?? [])}
      >
        {viewSwitch}
        <FilterSelect
          value={fwType}
          onChange={(v) => { setFwType(v); paging.reset(); }}
          options={[{ value: "MCU", label: "主控 MCU" }, { value: "SLOT", label: "仓门 SLOT" }, { value: "MODEM", label: "通信模组 MODEM" }]}
          allLabel="全部固件类型"
          aria-label="按固件类型筛选"
        />
        <FilterSelect
          value={status}
          onChange={(v) => { setStatus(v); paging.reset(); }}
          /* 选项由 RELEASE_STATUS 派生 —— 筛选项文案与徽标文案同源，改一处即可 */
          options={RELEASE_STATUS}
          allLabel="全部状态"
          aria-label="按固件版本状态筛选"
        />
      </Toolbar>

      <DataTable
        rowKey={(r: OtaRelease) => r.releaseNo}
        columns={cols}
        rows={data?.list}
        loading={isLoading}
        empty="暂无固件版本——投放前先在此登记固件包与校验和，投放列表里的版本号才有出处"
      />
      {data && <Pagination page={paging.page} size={paging.size} total={data.total} onPage={paging.setPage} onSize={paging.setSize} />}

      <FormDrawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        titleNew="新建固件版本"
        titleEdit={`编辑版本 ${form?.releaseNo ?? ""}`}
        isEdit={!!form?.releaseNo}
        fields={RELEASE_FIELDS}
        value={(form ?? {}) as Record<string, unknown>}
        onChange={(v) => setForm(v as Partial<OtaRelease>)}
        onSubmit={submit}
        submitting={save.isPending}
      />
    </div>
  );
}

/** 投放的逐设备任务明细：整体百分比是这批任务的均值，卡住时要看具体哪台、卡在哪一步。 */
/**
 * 调拨明细抽屉：单据只说「多少台」，**盘点对不上时要查的是「具体哪几台」**。
 * `checked` 是收货方逐台核对的结果 —— 没核到的那几台就是差异的落点，
 * 所以未核对的排在前面，不用翻着找。
 */
function TransferDetailDrawer({ transfer, onOpenChange }: { transfer: InventoryTransfer | null; onOpenChange: (o: boolean) => void }) {
  const { data, isLoading, error, refetch } = useQuery<InventoryTransferDetail>({
    queryKey: ["transfer-detail", transfer?.transferNo],
    queryFn: () => api.getInventoryTransfer(transfer!.transferNo),
    enabled: !!transfer,
  });
  const items = data?.items ?? [];
  const rows = [...items].sort((a, b) => Number(a.checked) - Number(b.checked));
  const checked = items.filter((i) => i.checked).length;

  const cols: Column<TransferItem>[] = [
    { header: "设备编号", cell: (i) => <span className="font-medium tabular-nums">{i.itemNo}</span> },
    { header: "核对", cell: (i) => (
      <StatusBadge map={ITEM_CHECKED} value={i.checked ? "CHECKED" : "UNCHECKED"} />
    ) },
  ];

  return (
    <Drawer
      open={!!transfer}
      onOpenChange={onOpenChange}
      width="w-[560px]"
      title={`调拨明细 ${transfer?.transferNo ?? ""}`}
      desc={transfer ? `${transfer.fromLocation} → ${transfer.toLocation} · 单据 ${Math.round(transfer.powerbankCount)} 台` : undefined}
    >
      {data && (
        // 单据台数与明细行数对不上，本身就是要查的线索，所以两个数都摆出来
        <div className="mb-3 txt-body text-muted-foreground">
          明细 {items.length} 台 · 已核对 {checked} · 待核对 {items.length - checked}
        </div>
      )}
      <DataTable
        rowKey={(i: TransferItem) => i.itemNo}
        columns={cols}
        rows={rows}
        loading={isLoading}
        error={error}
        onRetry={refetch}
        empty="这张调拨单没有逐台明细——按台数登记的旧单据不会留下明细行"
      />
    </Drawer>
  );
}

function OtaTasksDrawer({ rollout, onOpenChange }: { rollout: OtaRollout | null; onOpenChange: (o: boolean) => void }) {
  const { data, isLoading } = useQuery({
    queryKey: ["ota-tasks", rollout?.rolloutNo],
    queryFn: () => api.listOtaTasks(rollout!.rolloutNo),
    enabled: !!rollout,
  });
  const tally = (...st: OtaTask["status"][]) => (data ?? []).filter((t) => st.includes(t.status)).length;

  const cols: Column<OtaTask>[] = [
    { header: "任务号", cell: (t) => <span className="font-medium tabular-nums">{t.taskNo}</span> },
    { header: "机柜号", cell: (t) => <span className="tabular-nums">{t.cabinetNo}</span> },
    // 「升级前 → 目标」并排：跨版本升级失败率高，排障先看是从哪个版本跳上来的
    { header: "版本变化", cell: (t) => (
      <span className="tabular-nums text-xs">{t.previousVersion} <span className="text-muted-foreground">→</span> {rollout?.fwVersion}</span>
    ) },
    { header: "进度", cell: (t) => <Progress value={t.progress} total={100} /> },
    { header: "状态", cell: (t) => <StatusBadge map={TASK_STATUS} value={t.status} /> },
    { header: "失败原因", cell: (t) => t.error ? <span className="block max-w-[14rem] text-xs text-[var(--destructive)]">{t.error}</span> : <span className="text-muted-foreground">-</span> },
  ];

  return (
    <Drawer
      open={!!rollout}
      onOpenChange={onOpenChange}
      width="w-[760px]"
      title={`任务明细 ${rollout?.rolloutNo ?? ""}`}
      desc={rollout ? `${rollout.fwVersion} · ${rollout.strategy === "FULL" ? "全量" : "灰度"} · 整体 ${Math.round(rollout.progress)}%` : undefined}
    >
      {data && (
        <div className="mb-3 text-sm text-muted-foreground">
          共 {data.length} 台 · 成功 {tally("SUCCESS")} · 失败 {tally("FAILED")} · 回滚 {tally("ROLLED_BACK")} · 进行中 {tally("DOWNLOADING", "DOWNLOADED", "INSTALLING")} · 排队 {tally("PENDING")}
        </div>
      )}
      <DataTable
        rowKey={(t: OtaTask) => t.taskNo}
        columns={cols}
        rows={data}
        loading={isLoading}
        empty="该投放还没有派发到设备——投放刚建好、尚未生成逐台任务"
      />
    </Drawer>
  );
}

// ============================================================================
// 单柜指令下发（S8）：指令记录 tab 原先是只读流水——批量指令在机柜 tab、单柜指令只在
// /devices/detail，唯独「看着一条超时记录想立刻重发一次」的地方没有入口。
// 语义与详情页那套完全一致（同一个 api.sendCommand、同一份 COMMAND_TYPES 词表、
// 同样「不带仓位 = 任意仓」），差别只是这里要先选机柜。
// ============================================================================
type CmdDraft = { cabinetNo?: string; type?: string; slotIndex?: number | string };

const commandFields = (cabinets: Cabinet[], typeHint: string): FieldDef[] => [
  { key: "cabinetNo", label: "机柜", type: "select", required: true,
    options: [
      { value: "", label: "请选择机柜" },
      ...cabinets.map((c) => ({
        value: c.cabinetNo,
        // 离线态直接标在选项上：离线柜的指令只能排队，选之前就该知道
        label: `${c.cabinetNo} · ${c.locationName ?? "未上架"}${c.onlineStatus === "OFFLINE" ? "（离线）" : ""}`,
      })),
    ] },
  { key: "type", label: "指令", type: "select", required: true, help: typeHint,
    options: SINGLE_COMMANDS.map((c) => ({ value: c.value, label: c.label })) },
  { key: "slotIndex", label: "仓位号", type: "number", min: 1,
    disabledWhen: (v) => !SLOT_COMMANDS.includes(String(v.type)),
    help: "仅弹仓/锁仓用得上：锁仓必填，弹仓留空即任意可弹仓" },
];

function SendCommandDrawer({ draft, onOpenChange }: { draft: CmdDraft | null; onOpenChange: (o: boolean) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<CmdDraft>({});
  // 「重新下发」是带着某条记录的机柜/指令/仓位打开的，每次开抽屉都以入参为准重置
  useEffect(() => { if (draft) setForm(draft); }, [draft]);

  const cabsQ = useQuery({
    queryKey: ["cabinets", "command-options"],
    queryFn: () => api.listCabinets({ size: UNPAGED_SIZE }),
    enabled: !!draft,
  });
  const cabs = cabsQ.data?.list ?? [];
  const picked = cabs.find((c) => c.cabinetNo === form.cabinetNo);

  const send = useMutation({
    mutationFn: (v: CmdDraft) =>
      api.sendCommand(v.cabinetNo!, v.type!, v.slotIndex ? { slotIndex: Number(v.slotIndex) } : undefined),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["devices"] }); // 指令记录列表就在这一页，下发后立刻刷新
      notify.success(`指令已下发：${r.commandId}（记录先记「已下发」，设备回执后才转「已确认」）`);
      onOpenChange(false);
    },
  });

  const submit = () => {
    // 与落库校验共用 types 层那份 SLOT_REQUIRED_COMMANDS：只是把报错提前到点保存之前，规则不另写一套
    if (SLOT_REQUIRED_COMMANDS.includes(form.type as CommandType) && !form.slotIndex) {
      notify.error("锁仓必须指定仓位：不说明锁哪个仓的指令没有意义");
      return;
    }
    send.mutate(form);
  };

  const cmd = SINGLE_COMMANDS.find((c) => c.value === form.type);
  const hint = [
    cmd?.hint,
    picked?.onlineStatus === "OFFLINE" ? "该机柜当前离线，指令会排队等上线后执行" : null,
    picked ? `共 ${picked.slotTotal} 仓` : null,
  ].filter(Boolean).join(" · ");

  return (
    <FormDrawer
      open={!!draft}
      onOpenChange={onOpenChange}
      titleNew="下发远程指令"
      // 指令不是可编辑实体：每次下发都是一条新记录，「重新下发」也是新发一条，故恒 isEdit=false
      titleEdit="下发远程指令"
      isEdit={false}
      fields={commandFields(cabs, hint)}
      value={form as Record<string, unknown>}
      onChange={(v) => setForm(v as CmdDraft)}
      onSubmit={submit}
      submitting={send.isPending}
    />
  );
}

function DevicesInner() {
  const qc = useQueryClient();
  const allow = useCan();
  const paging = usePaging();
  const tabs = useNavTabs("/devices", TAB_KEYS);
  const { tab, setTab } = usePageTab(tabs, () => { paging.reset(); setKeyword(""); });
  const [keyword, setKeyword] = useState("");
  const [pbForm, setPbForm] = useState<Partial<Powerbank> | null>(null);
  const [invForm, setInvForm] = useState<Partial<InventoryTransfer> | null>(null);
  const [invDetail, setInvDetail] = useState<InventoryTransfer | null>(null);
  const [otaForm, setOtaForm] = useState<Partial<OtaRollout> | null>(null);
  // 指令记录 tab 的下发抽屉：null = 关，对象 = 打开并按内容预填（「重新下发」带着原记录进来）
  const [cmdDraft, setCmdDraft] = useState<CmdDraft | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  // 固件 OTA 的投放/版本库切换 + 任务明细抽屉的当前投放
  const [otaView, setOtaView] = useState<"rollouts" | "releases">("rollouts");
  const [taskRollout, setTaskRollout] = useState<OtaRollout | null>(null);
  // 监控 tab 的列表/地图切换（G4）。地图按**站点**聚合撒点，不逐台机柜——上千机柜会卡。
  const [monitorView, setMonitorView] = useState<"list" | "map">("list");
  const sitesQ = useQuery({
    queryKey: ["monitor-sites"],
    queryFn: () => api.listSites({ page: 1, size: UNPAGED_SIZE }),
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

  const isCabinets = tab === "cabinets";
  // 版本库视图与自建 tab 同类：自带筛选/查询/分页，页面共用的 Toolbar 与 q 都不参与
  const isReleases = tab === "ota" && otaView === "releases";
  const isStandalone = STANDALONE_TABS.includes(tab) || isReleases;
  const canEditCabinet = allow("device:cabinet:update");
  // 自动开工单借用工单域的码：能不能开单由工单域授权说了算，不是「有设备权限就能批量开单」
  const canRaiseWo = allow("workorder:wo:create");
  const canEditCode = canEditCabinet;
  const canEditPowerbank = allow("device:powerbank:update");
  const canEditInventory = allow("device:inventory:update");
  const canEditOta = allow("device:ota:publish");
  // 指令下发与「看指令记录」是两个权限：能看流水不等于能动设备（后端 sendCommand 也这么标）
  const canSendCommand = allow("device:command:send");
  // 版本库的**读**在后端也要 device:ota:manage（GET /ota-releases 就是这么标的），
  // 无权时进去只会吃 403，故连入口段控都不给
  const canManageOta = allow("device:ota:manage");
  const otaViewSwitch = canManageOta ? (
    <ViewSwitch
      label="固件 OTA 视图"
      value={otaView}
      options={[{ value: "rollouts", label: "投放列表" }, { value: "releases", label: "固件版本库" }] as const}
      onChange={(v) => { setOtaView(v); paging.reset(); setKeyword(""); }}
    />
  ) : null;

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

  // 故障 → 自动开工单联动。这是告警规则引擎缺的最后一环：告警码字典的 autoWorkOrder
  // 开关一直存在、转工单端点也一直存在且幂等，但没有任何东西把两者连起来。
  // **天然幂等**（逐条复用 alarmNo 幂等键），所以重复点只会 skipped++，不会重复开单。
  const autoWo = useMutation({
    mutationFn: () => api.autoRaiseWorkOrders(),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["device"] });
      qc.invalidateQueries({ queryKey: ["alarm"] });
      notify.success(
        r.created.length > 0
          ? `已为 ${r.created.length} 条故障告警开单（${r.created.map((x) => x.woNo).join("、")}）${r.skipped ? `；${r.skipped} 条已有工单未重复开` : ""}`
          : `命中 ${r.eligible} 条故障告警，均已有工单，未重复开单`,
      );
    },
  });
  const askAutoWo = async () => {
    const ok = await confirm({
      title: "为故障告警自动开工单",
      desc: "按「告警代码」里勾选了自动开工单的码，为所有未关闭且尚无工单的告警各开一张维修工单，并派给运维。已有工单的告警会跳过，不会重复开。",
      confirmText: "开始处理",
      cancelText: "再想想",
    });
    if (ok) autoWo.mutate();
  };

  const q = useQuery<PageResult<Row>>({
    queryKey: ["devices", tab, paging.page, paging.size, keyword, showArchived],
    queryFn: () =>
      tab === "powerbanks" ? api.listPowerbanks({ page: paging.page, size: paging.size, keyword, showArchived: showArchived || undefined })
      : tab === "monitor" ? api.listCabinetMonitor({ page: paging.page, size: paging.size, keyword })
      : tab === "commands" ? api.listCommandRecords({ page: paging.page, size: paging.size, keyword })
      : tab === "inventory" ? api.listInventoryTransfers({ page: paging.page, size: paging.size, keyword })
      : api.listOtaRollouts({ page: paging.page, size: paging.size, keyword }),
    placeholderData: keepWithinTab(tab),
    enabled: !isStandalone,
  });

  const editCell = (on: () => void, can: boolean) =>
    can ? <Button size="sm" variant="outline" onClick={on}>编辑</Button> : <span className="text-muted-foreground">-</span>;

  /*
   * 厂商主数据。与 ImportCabinetsDrawer 里那个是**同一个 queryKey** ——
   * React Query 按 key 去重共用缓存，不会多发一次请求。
   */
  const vendorsQ = useQuery({ queryKey: ["vendors"], queryFn: () => api.listVendors() });
  /** 厂商编码 → 名字。取不到（未加载 / 已下线的厂商）时退回编码本身，不显示空白。 */
  const vendorName = (code: string | null) =>
    (vendorsQ.data ?? []).find((v) => v.vendorCode === code)?.name ?? code ?? "-";

  const pbColsFull: Column<Powerbank>[] = [
    ...pbColsOf(vendorName),
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
  const invColsFull: Column<InventoryTransfer>[] = [
    ...invColsWith(setInvDetail),
    { header: "操作", cell: (r) => editCell(() => setInvForm(r), canEditInventory) },
  ];
  // 指令记录不可编辑（历史流水），能做的只有「照这条再发一次」——超时/失败的指令最常见的处置
  const cmdColsFull: Column<CommandRecord>[] = canSendCommand ? [...cmdCols, {
    header: "操作",
    cell: (r) => (
      <Button size="sm" variant="outline"
        onClick={() => setCmdDraft({ cabinetNo: r.cabinetNo, type: r.type, slotIndex: r.slotIndex ?? "" })}>
        重新下发
      </Button>
    ),
  }] : cmdCols;
  const otaColsFull: Column<OtaRollout>[] = [...otaCols, {
    header: "操作",
    // 任务明细只要 device:ota:read（能看这张 tab 就有），故不额外判权
    cell: (r) => (
      <div className="flex items-center gap-2">
        <Button size="sm" variant="outline" onClick={() => setTaskRollout(r)}>任务明细</Button>
        {canEditOta && <Button size="sm" variant="outline" onClick={() => setOtaForm(r)}>编辑</Button>}
      </div>
    ),
  }];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {!isStandalone && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); paging.reset(); }}
          searchPlaceholder={SEARCH_HINT[tab]}
          onAdd={
            tab === "powerbanks" && canEditPowerbank ? () => setPbForm({ cabinetNo: "", battery: 100, status: "IN_CABINET", health: "OK", cycles: 0 })
            : tab === "inventory" && canEditInventory ? () => setInvForm({ fromLocation: "", toLocation: "", powerbankCount: 1, status: "DRAFT", operator: "" })
            : tab === "ota" && canEditOta ? () => setOtaForm({ fwVersion: "", vendorCode: "", strategy: "GRAY", progress: 0, status: "PENDING" })
            // 指令记录 tab 的下发入口：默认预填「重启」（最常用的整机处置），机柜由用户选
            : tab === "commands" && canSendCommand ? () => setCmdDraft({ type: "REBOOT", cabinetNo: "", slotIndex: "" })
            : undefined
          }
          addLabel={tab === "powerbanks" ? "新增充电宝" : tab === "inventory" ? "新增调拨单" : tab === "ota" ? "新增发布单" : tab === "commands" ? "下发指令" : undefined}
          onExport={EXPORTS[tab] ? () => EXPORTS[tab].run(q.data?.list ?? []) : undefined}
        >
          {/* 监控 tab：列表 / 地图 双视图（G4）。地图按站点聚合，点 marker 回列表并带上站点筛选。 */}
          {tab === "monitor" && canRaiseWo && (
            <Button size="sm" variant="outline" disabled={autoWo.isPending} onClick={askAutoWo}>
              {autoWo.isPending ? "处理中…" : "故障自动开工单"}
            </Button>
          )}
          {tab === "monitor" && (
            <ViewSwitch
              label="监控视图"
              value={monitorView}
              options={[{ value: "list", label: "列表" }, { value: "map", label: "地图" }] as const}
              onChange={setMonitorView}
            />
          )}
          {/* 固件 OTA：投放列表 / 版本库 双视图。版本库是投放引用的「货架」，同一张 tab 内切换 */}
          {tab === "ota" && otaViewSwitch}
          {/* 充电宝是可归档实体，故只有它需要「显示已归档」开关 */}
          {tab === "powerbanks" && (
            <ShowArchivedToggle
              checked={showArchived}
              onChange={(v) => { setShowArchived(v); paging.reset(); }}
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
          loading={q.isLoading} error={q.error} onRetry={q.refetch}
          rowClassName={archivedRowClass}
          empty="暂无充电宝——新到货的充电宝需先入库建档；已归档的需勾选「显示已归档」才会出现"
        />
      )}
      {tab === "monitor" && (monitorView === "list"
        ? <DataTable rowKey={(r: CabinetMonitor) => r.cabinetNo} columns={monCols} rows={q.data?.list as CabinetMonitor[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} />
        : <SiteMap points={mapPoints} onSelect={(pt) => { setKeyword(pt.name); setMonitorView("list"); paging.reset(); }} />)}
      {tab === "commands" && (
        <>
          {!canSendCommand && <ReadOnlyNotice what="指令下发" perm="device:command:send" note="历史指令流水仍可查看" />}
          <DataTable rowKey={(r: CommandRecord) => r.commandId} columns={cmdColsFull} rows={q.data?.list as CommandRecord[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} />
        </>
      )}
      {tab === "inventory" && <DataTable rowKey={(r: InventoryTransfer) => r.transferNo} columns={invColsFull} rows={q.data?.list as InventoryTransfer[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} />}
      {isReleases && <OtaReleasesTab canManage={canManageOta} viewSwitch={otaViewSwitch} />}
      {tab === "ota" && !isReleases && <DataTable rowKey={(r: OtaRollout) => r.rolloutNo} columns={otaColsFull} rows={q.data?.list as OtaRollout[]} loading={q.isLoading} error={q.error} onRetry={q.refetch} />}
      {!isStandalone && q.data && <Pagination page={paging.page} size={paging.size} total={q.data.total} onPage={paging.setPage} onSize={paging.setSize} />}
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
      <TransferDetailDrawer transfer={invDetail} onOpenChange={(o) => !o && setInvDetail(null)} />
      <OtaTasksDrawer rollout={taskRollout} onOpenChange={(o) => !o && setTaskRollout(null)} />
      <SendCommandDrawer draft={cmdDraft} onOpenChange={(o) => !o && setCmdDraft(null)} />
    </div>
  );
}

export default function DevicesPage() {
  return <Suspense fallback={null}><DevicesInner /></Suspense>;
}
