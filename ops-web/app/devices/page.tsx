"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Pagination } from "@/components/ui/misc";
import { Input, Select } from "@/components/ui/input";
import { DateInput } from "@/components/ui/date-input";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Progress } from "@/components/ui/progress";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CabinetStatusBadge, OnlineBadge } from "@/components/status";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { exportCsv } from "@/lib/export-csv";
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

// —— 机柜台账（原有视图，保留全部筛选/搜索/详情能力）——
function CabinetsTab() {
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [online, setOnline] = useState("");
  const [status, setStatus] = useState("");

  const { data, isLoading } = useQuery({
    queryKey: ["cabinets", page, keyword, online, status],
    queryFn: () => api.listCabinets({ page, size: SIZE, keyword, onlineStatus: online || undefined, status: status || undefined }),
    placeholderData: keepPreviousData,
  });

  const cabCols: Column<Cabinet>[] = [
    { header: "柜机号", cell: (c) => <span className="font-medium">{c.cabinetNo}</span> },
    { header: "点位", cell: (c) => <span className="text-muted-foreground">{c.locationName}</span> },
    { header: "供应商", cell: (c) => c.vendorCode },
    { header: "可借/仓位", cell: (c) => <span className="tabular-nums">{c.availableCount}/{c.slotTotal}</span> },
    { header: "在线", cell: (c) => <OnlineBadge s={c.onlineStatus} /> },
    { header: "状态", cell: (c) => <CabinetStatusBadge s={c.status} /> },
    { header: "固件", cell: (c) => <span className="text-muted-foreground">{c.fwVersion}</span> },
    { header: "最后心跳", cell: (c) => <span className="text-muted-foreground">{fmtTime(c.lastHeartbeatAt)}</span> },
    { header: "操作", cell: (c) => <Link className="text-primary hover:underline" href={`/devices/detail?no=${c.cabinetNo}`}>详情</Link> },
  ];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input
          className="w-64"
          placeholder="搜索柜机号 / 点位"
          value={keyword}
          onChange={(e) => { setKeyword(e.target.value); setPage(1); }}
        />
        <Select value={online} onChange={(e) => { setOnline(e.target.value); setPage(1); }}>
          <option value="">全部在线态</option>
          <option value="ONLINE">在线</option>
          <option value="OFFLINE">离线</option>
        </Select>
        <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="">全部状态</option>
          <option value="DEPLOYED">在用</option>
          <option value="FAULT">故障</option>
          <option value="RETIRED">报废</option>
        </Select>
      </div>

      <DataTable rowKey={(c: Cabinet) => c.cabinetNo} columns={cabCols} rows={data?.list} loading={isLoading} empty="无设备" />
      {data && <Pagination page={page} size={SIZE} total={data.total} onPage={setPage} />}
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
  useEffect(() => { if (qTab && TABS.some((t) => t.key === qTab)) { setTab(qTab); setPage(1); } }, [qTab]);

  const isCabinets = tab === "cabinets";
  // 自建 tab 自带筛选/查询/分页，页面共用的 Toolbar 与 q 不参与
  const isStandalone = STANDALONE_TABS.includes(tab);
  const canEditCode = allow("device:cabinet:update");
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

  const q = useQuery<PageResult<Row>>({
    queryKey: ["devices", tab, page, keyword],
    queryFn: () =>
      tab === "powerbanks" ? api.listPowerbanks({ page, size: SIZE, keyword })
      : tab === "monitor" ? api.listCabinetMonitor({ page, size: SIZE, keyword })
      : tab === "commands" ? api.listCommandRecords({ page, size: SIZE, keyword })
      : tab === "inventory" ? api.listInventoryTransfers({ page, size: SIZE, keyword })
      : api.listOtaRollouts({ page, size: SIZE, keyword }),
    placeholderData: keepPreviousData,
    enabled: !isStandalone,
  });

  const editCell = (on: () => void, can: boolean) =>
    can ? <Button size="sm" variant="outline" onClick={on}>编辑</Button> : <span className="text-muted-foreground">-</span>;

  const pbColsFull: Column<Powerbank>[] = [...pbCols, { header: "操作", cell: (r) => editCell(() => setPbForm(r), canEditPowerbank) }];
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
        />
      )}

      {isCabinets && <CabinetsTab />}
      {tab === "logs" && <LogsTab />}
      {tab === "codes" && <CodesTab canEdit={canEditCode} />}
      {tab === "powerbanks" && <DataTable rowKey={(r: Powerbank) => r.powerbankNo} columns={pbColsFull} rows={q.data?.list as Powerbank[]} loading={q.isLoading} />}
      {tab === "monitor" && <DataTable rowKey={(r: CabinetMonitor) => r.cabinetNo} columns={monCols} rows={q.data?.list as CabinetMonitor[]} loading={q.isLoading} />}
      {tab === "commands" && <DataTable rowKey={(r: CommandRecord) => r.commandId} columns={cmdCols} rows={q.data?.list as CommandRecord[]} loading={q.isLoading} />}
      {tab === "inventory" && <DataTable rowKey={(r: InventoryTransfer) => r.transferNo} columns={invColsFull} rows={q.data?.list as InventoryTransfer[]} loading={q.isLoading} />}
      {tab === "ota" && <DataTable rowKey={(r: OtaRollout) => r.rolloutNo} columns={otaColsFull} rows={q.data?.list as OtaRollout[]} loading={q.isLoading} />}
      {!isStandalone && q.data && <Pagination page={page} size={SIZE} total={q.data.total} onPage={setPage} />}

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
