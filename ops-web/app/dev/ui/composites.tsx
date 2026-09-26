"use client";

// 第二层：组合件（components/ui/*，由原语拼成的通用交互单元，仍无业务语义）。
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { DataTable, type Column, type SortDir } from "@/components/ui/data-table";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import { Drawer, Field } from "@/components/ui/drawer";
import { Toolbar } from "@/components/ui/toolbar";
import { TabHeader } from "@/components/ui/tab-header";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { MultiSelect } from "@/components/ui/multi-select";
import { FileField } from "@/components/ui/file-field";
import type { FileCategory, FileRef } from "@/lib/types";
import { StatusBadge, statusOptions, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { Timeline, type TimelineItem } from "@/components/ui/timeline";
import { SiteMap, type MapPoint } from "@/components/ui/site-map";
import { notify } from "@/lib/notify";
import { Section, Row, Flaw, Hint, Missing } from "./kit";
import { Probe } from "./probe";

export function CompositeSections() {
  return (
    <>
      <DataTableSection />
      <FormDrawerSection />
      <DrawerSection />
      <ToolbarSection />
      <TabHeaderSection />
      <ConfirmSection />
      <MultiSelectSection />
      <FileFieldSection />
      <StatusBadgeSection />
      <FilterSelectSection />
      <TimelineSection />
      <SiteMapSection />
      <ToasterSection />
    </>
  );
}

// ────────────────────────────────────────────────────────────── DataTable
type Demo = { id: string; site: string; slots: number; status: keyof typeof DEMO_STATUS; archivedAt?: string | null };

const DEMO_STATUS = {
  DEPLOYED: { label: "已部署", tone: "success" },
  FAULT: { label: "故障", tone: "danger" },
  RETIRED: { label: "已退役", tone: "muted" },
} satisfies StatusMap<"DEPLOYED" | "FAULT" | "RETIRED">;

const DEMO_ROWS: Demo[] = [
  { id: "CAB-001", site: "JBR 星巴克", slots: 12, status: "DEPLOYED" },
  { id: "CAB-002", site: "Dubai Mall B1", slots: 24, status: "FAULT" },
  { id: "CAB-003", site: "机场 T3 到达层", slots: 8, status: "RETIRED", archivedAt: "2026-06-01T10:00:00Z" },
];

function DataTableSection() {
  const [sel, setSel] = React.useState<string[]>(["CAB-002"]);
  const [sortKey, setSortKey] = React.useState<string | undefined>("slots");
  const [sortDir, setSortDir] = React.useState<SortDir>("asc");

  const cols: Column<Demo>[] = [
    { header: "编号", cell: (r) => <span className="font-medium">{r.id}</span>, sortKey: "id" },
    { header: "站点", cell: (r) => r.site },
    { header: "仓位", cell: (r) => <span className="tabular-nums">{r.slots}</span>, className: "text-end", sortKey: "slots" },
    { header: "状态", cell: (r) => <StatusBadge map={DEMO_STATUS} value={r.status} /> },
  ];

  return (
    <Section id="data-table" name="DataTable" layer="组合件" file="ui/data-table.tsx" purpose="列表页表格：列配置 + 加载/空态 + 行选择/展开/排序/行样式。">
      <Row label="loading" note="rows=undefined 且 loading=true 时才出骨架" stack>
        <DataTable columns={cols} rows={undefined} loading rowKey={(r: Demo) => r.id} />
      </Row>
      <Row label="empty（默认文案）" stack>
        <DataTable columns={cols} rows={[]} rowKey={(r) => r.id} />
      </Row>
      <Row label="empty（推荐写法：说清为什么空）" stack>
        <DataTable columns={cols} rows={[]} rowKey={(r) => r.id} empty="当前筛选条件下没有机柜。清空「状态」筛选，或到「设备台账」新增一台。" />
      </Row>
      <Row label="有数据" stack>
        <DataTable columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id} />
      </Row>
      <Row label="行选择（含半选表头）" note={`已选 ${sel.length} 行`} stack>
        <DataTable columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id} selectable selectedKeys={sel} onSelectedChange={setSel} />
      </Row>
      <Row label="排序（受控）" note={`当前 ${sortKey} ${sortDir}`} stack>
        <DataTable
          columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id}
          sortKey={sortKey} sortDir={sortDir}
          onSortChange={(k, d) => { setSortKey(k); setSortDir(d); }}
        />
      </Row>
      <Row label="可展开" note="第三行故意不给展开内容 —— 该行不出箭头" stack>
        <DataTable
          columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id}
          expandable={(r) => (r.status === "RETIRED" ? null : <div className="text-xs text-muted-foreground">展开内容：{r.id} 的仓位明细…</div>)}
        />
      </Row>
      <Row label="rowClassName（已归档弱化）" stack>
        <DataTable
          columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id}
          rowClassName={(r) => (r.archivedAt ? "opacity-60" : undefined)}
        />
      </Row>
      <Row label="全能力叠加" stack>
        <DataTable
          columns={cols} rows={DEMO_ROWS} rowKey={(r) => r.id}
          selectable selectedKeys={sel} onSelectedChange={setSel}
          expandable={(r) => <div className="text-xs text-muted-foreground">{r.id} 明细</div>}
          sortKey={sortKey} sortDir={sortDir} onSortChange={(k, d) => { setSortKey(k); setSortDir(d); }}
          rowClassName={(r) => (r.archivedAt ? "opacity-60" : undefined)}
        />
      </Row>

      <Flaw>
        <b>行选择 checkbox 是就地实现的裸 <code>&lt;input type=&quot;checkbox&quot;&gt;</code></b>（组件内 RowCheckbox，未导出），
        Tree 里还有第二份（TriCheckbox）。两份都用 <code>accent-[var(--primary)]</code> 交给浏览器画，
        所以**不跟皮肤/暗色走**，也**没有 focus-visible 环**。
      </Flaw>
      <Flaw>
        <b>排序表头按钮无 focus 环</b>（<code>rounded-md</code> + hover 变色，键盘不可见）；
        展开箭头按钮同样。
      </Flaw>
      <Flaw>
        <b>loading 只在 <code>rows === undefined</code> 时生效</b>：翻页/改筛选时 rows 还是上一页的数组，
        loading=true 也不出骨架，表格静止不动 —— 看起来像卡住了。
      </Flaw>
      <Flaw><b>行高不走 <code>--row-h</code></b>（继承 Table 的硬写值），密度开关对列表页无效。</Flaw>
      <Missing>无 error 态。请求失败时调用方只能把 rows 传空数组，于是「失败」和「没有数据」长得一模一样。</Missing>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── FormDrawer
/**
 * @form-none 组件展示页的假表单：onSubmit 只弹一个 toast，不提交到任何端点。
 * 挂 @form 是假的 —— 它没有「发到哪」这件事，挂了反而会把展示字段当成真契约核。
 */
const DEMO_FIELDS: FieldDef[] = [
  { key: "code", label: "机柜编号", required: true, readOnlyOnEdit: true, placeholder: "CAB-000123", section: "基本信息",
    pattern: { re: "^CAB-\\d{6}$", msg: "格式应为 CAB- + 6 位数字" } },
  { key: "name", label: "名称", required: true, maxLength: 20, section: "基本信息", help: "展示在运营端与 C 端的名称" },
  { key: "slots", label: "仓位数", type: "number", required: true, min: 4, max: 48, section: "基本信息" },
  { key: "status", label: "状态", type: "select", required: true, section: "基本信息",
    options: [{ value: "", label: "请选择" }, ...statusOptions(DEMO_STATUS)] },
  { key: "online", label: "启用", type: "switch", section: "运行配置" },
  { key: "offlineReason", label: "停用原因", type: "text", section: "运行配置",
    disabledWhen: (v) => !!v.online, help: "「启用」打开时本字段自动禁用并清空（联动演示）" },
  { key: "onlineAt", label: "上线日期", type: "date", section: "运行配置" },
  { key: "countries", label: "适用国家", type: "multiselect", csv: true, required: true, section: "运行配置",
    options: [{ value: "AE", label: "阿联酋" }, { value: "SA", label: "沙特" }, { value: "QA", label: "卡塔尔" }] },
  { key: "secret", label: "设备密钥", type: "password", section: "运行配置" },
  { key: "memo", label: "备注", type: "textarea", rows: 3, maxLength: 50, section: "运行配置" },
];

function FormDrawerSection() {
  const [open, setOpen] = React.useState(false);
  const [isEdit, setIsEdit] = React.useState(false);
  const [submitting, setSubmitting] = React.useState(false);
  const [value, setValue] = React.useState<Record<string, unknown>>({ code: "CAB-000123", name: "JBR 星巴克 3 号柜", online: true });

  const openWith = (edit: boolean, v: Record<string, unknown>, busy = false) => {
    setIsEdit(edit); setValue(v); setSubmitting(busy); setOpen(true);
  };

  return (
    <Section id="form-drawer" name="FormDrawer" layer="组合件" file="ui/form-drawer.tsx" purpose="配置化编辑抽屉：FieldDef[] → 表单 + 校验 + 分区 + 联动。">
      <Row label="新增（空表单）" note="点保存会一次性暴露全部必填错误">
        <Button size="sm" onClick={() => openWith(false, {})}>打开·新增</Button>
      </Row>
      <Row label="编辑（有值 + 主键只读）">
        <Button size="sm" variant="outline" onClick={() => openWith(true, { code: "CAB-000123", name: "JBR 星巴克 3 号柜", slots: 12, status: "DEPLOYED", online: true, countries: "AE,SA", onlineAt: "2026-01-15", memo: "季度巡检已完成" })}>打开·编辑</Button>
      </Row>
      <Row label="校验错误态" note="预置了越界/超长/格式错的值，打开即见红字 + 红 ring">
        <Button size="sm" variant="outline" onClick={() => openWith(true, { code: "WRONG-1", name: "这个名字非常非常非常非常非常非常长超过了二十个字上限", slots: 999, status: "", online: false, memo: "备"​.repeat(60) })}>打开·全是错</Button>
      </Row>
      <Row label="submitting（保存按钮禁用）">
        <Button size="sm" variant="outline" onClick={() => openWith(true, { code: "CAB-000123", name: "提交中" }, true)}>打开·提交中</Button>
      </Row>
      <Row label="字段类型覆盖" note="text / number / select / switch / password / textarea / date / multiselect 全在同一个抽屉里">
        <span className="text-xs text-muted-foreground">打开上面任一个即可看到全部 8 种控件</span>
      </Row>

      <FormDrawer
        open={open} onOpenChange={setOpen}
        titleNew="新增机柜" titleEdit="编辑机柜" isEdit={isEdit}
        fields={DEMO_FIELDS} value={value} onChange={setValue}
        onSubmit={() => { notify.success("演示：提交成功"); setOpen(false); }}
        submitting={submitting}
      />

      <Flaw>
        <b>textarea 是就地实现的裸 <code>&lt;textarea&gt;</code></b>（不是原语），
        圆角 <code>rounded-lg</code> 与 Input 的 11px 不一致 —— 同一个表单里输入框和多行框圆角不同。
      </Flaw>
      <Flaw>
        <b>switch 也是就地实现的裸 <code>&lt;button role=&quot;switch&quot;&gt;</code></b>，
        无 focus-visible 环 —— 键盘用户既看不到焦点也不知道它可切换。
      </Flaw>
      <Flaw>
        <b>错误提示 <code>text-destructive</code> 压在卡片白底上</b>：用的是语义色原值不是 <code>--destructive-ink</code>，
        11px 小字，对比度见下方读数。
        <div className="mt-2"><Probe><span className="text-xs text-destructive">请输入数字</span></Probe></div>
      </Flaw>
      <Flaw>
        <b>必填星号只有颜色没有语义</b>：<code>&lt;span className=&quot;text-destructive&quot;&gt;*&lt;/span&gt;</code>，
        控件上没有 <code>aria-required</code> / <code>aria-invalid</code> / <code>aria-describedby</code>，
        读屏用户拿不到「这项必填」「错在哪」。
      </Flaw>
      <Hint>
        「保存按钮不因校验错误而禁用」是<b>刻意设计</b>（注释里有理由），不是缺陷 —— 别在 P2 顺手改掉。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Drawer / Field
function DrawerSection() {
  const [open, setOpen] = React.useState(false);
  const [wide, setWide] = React.useState(false);
  return (
    <Section id="drawer" name="Drawer / Field" layer="组合件" file="ui/drawer.tsx" purpose="右侧抽屉 + 详情行（Field 全站唯一一份）。">
      <Row label="默认宽（440px）"><Button size="sm" onClick={() => { setWide(false); setOpen(true); }}>打开抽屉</Button></Row>
      <Row label="宽抽屉（720px）"><Button size="sm" variant="outline" onClick={() => { setWide(true); setOpen(true); }}>打开宽抽屉</Button></Row>
      <Row label="Field · 默认（mb-4 自带间距）" stack>
        <div className="max-w-sm rounded-card bg-muted/40 p-4">
          <Field label="机柜编号">CAB-000123</Field>
          <Field label="所在站点">迪拜 · JBR 星巴克</Field>
          <Field label="状态"><StatusBadge map={DEMO_STATUS} value="DEPLOYED" /></Field>
        </div>
      </Row>
      <Row label="Field · 放进 grid（传 mb-0）" stack>
        <div className="grid max-w-lg grid-cols-2 gap-4 rounded-card bg-muted/40 p-4">
          <Field className="mb-0" label="机柜编号">CAB-000123</Field>
          <Field className="mb-0" label="仓位数">12</Field>
          <Field className="mb-0" label="空值">-</Field>
          <Field className="mb-0" label="超长值"><span className="break-all">{"很长的值".repeat(10)}</span></Field>
        </div>
      </Row>
      <Row label="Field 标签对比度"><Probe pick={(r) => r.querySelector(".text-xs")}><Field label="标签文字">值</Field></Probe></Row>

      <Drawer
        open={open} onOpenChange={setOpen}
        title="机柜详情" desc="CAB-000123 · JBR 星巴克"
        width={wide ? "w-[720px]" : undefined}
        footer={<><Button variant="outline" onClick={() => setOpen(false)}>关闭</Button><Button onClick={() => setOpen(false)}>确定</Button></>}
      >
        <Field label="编号">CAB-000123</Field>
        <Field label="状态"><StatusBadge map={DEMO_STATUS} value="FAULT" /></Field>
        <Field label="长内容">{"抽屉正文可滚动。".repeat(40)}</Field>
      </Drawer>

      <Flaw>
        <b>层级硬写 <code>z-40/z-50</code></b>，没走 <code>z-[var(--z-drawer)]/[var(--z-dialog)]</code>。
      </Flaw>
      <Flaw>
        <b>阴影用 <code>shadow-xl</code>（Tailwind 默认阶）</b>，不是 token 里为浮层准备的 <code>shadow-pop</code>。
        阴影阶就此形同虚设：定义了两档，浮层一档都没用。
      </Flaw>
      <Flaw>
        <b>抽屉是直角</b>：<code>--r-sheet</code>（20px）为「抽屉/弹层等大面」而设，Drawer 完全没用它。
        四档圆角里目前<b>只有 sheet 一档没有任何调用点</b>。
      </Flaw>
      <Flaw><b>关闭按钮 <code>rounded-md</code> 且无 focus 环</b>。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Toolbar
function ToolbarSection() {
  const [q, setQ] = React.useState("");
  const [status, setStatus] = React.useState("");
  const [n, setN] = React.useState(0);
  return (
    <Section id="toolbar" name="Toolbar" layer="组合件" file="ui/toolbar.tsx" purpose="列表页工具条：搜索 + 筛选槽 + 导出/新增；选中时整条切批量操作条。">
      <Row label="仅搜索" stack><Toolbar search={q} onSearch={setQ} /></Row>
      <Row label="搜索 + 筛选 + 导出 + 新增" stack>
        <Toolbar search={q} onSearch={setQ} onExport={() => notify.info("演示：导出")} onAdd={() => notify.info("演示：新增")}>
          <FilterSelect value={status} onChange={setStatus} options={DEMO_STATUS} allLabel="全部状态" />
        </Toolbar>
      </Row>
      <Row label="canAdd=false（无权限时不出新增）" stack>
        <Toolbar search={q} onSearch={setQ} onAdd={() => {}} canAdd={false} onExport={() => {}} />
      </Row>
      <Row label="无搜索（onSearch 不传）" stack><Toolbar onAdd={() => {}} /></Row>
      <Row label="批量操作条（selectedCount>0）" stack>
        <Toolbar
          selectedCount={3}
          batchActions={<><Button size="sm" variant="secondary">批量启用</Button><Button size="sm" variant="destructive">批量归档</Button></>}
          onClearSelection={() => setN(n + 1)}
        />
      </Row>
      <Flaw><b>圆角用 <code>rounded-[var(--radius)]</code></b>（批量条），应为 <code>rounded-card</code>。</Flaw>
      <Flaw>
        <b>搜索框没有清空按钮、没有防抖</b>：每键一个字符就触发一次筛选（列表页实测 300ms 内会连发多次）。
      </Flaw>
      <Hint>搜索框用 <code>rounded-full</code> 是刻意的（对齐 C 端首页搜索），与表单 Input 的 11px 有意不同。</Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── TabHeader
function TabHeaderSection() {
  const [v, setV] = React.useState("t1");
  return (
    <Section id="tab-header" name="TabHeader" layer="组合件" file="ui/tab-header.tsx" purpose="页内 tab 条：标题即当前子功能，悬停/点击标题才滑出 tab 条（含分期屏蔽）。">
      <Row label="多 tab（悬停标题展开）" note="鼠标移到标题上，或点标题固定展开" stack>
        <TabHeader
          tabs={[{ key: "t1", label: "设备台账" }, { key: "t2", label: "仓位明细" }, { key: "t3", label: "指令日志" }]}
          value={v} onChange={setV}
          action={<Button size="sm">新增机柜</Button>}
        />
      </Row>
      <Row label="单 tab（退化为纯标题）" stack>
        <TabHeader tabs={[{ key: "only", label: "系统参数" }]} value="only" onChange={() => {}} />
      </Row>
      <Row label="无 action" stack>
        <TabHeader tabs={[{ key: "a", label: "A" }, { key: "b", label: "B" }]} value="a" onChange={() => {}} />
      </Row>
      <Flaw>
        <b>标题区是 <code>&lt;div role=&quot;button&quot;&gt;</code> 而不是 <code>&lt;button&gt;</code></b>，
        自己补了 tabIndex 与 Enter/Space 处理，但<b>没有 focus-visible 环</b> —— 键盘用户看不到自己站在哪。
      </Flaw>
      <Flaw>
        <b>「悬停才出现」是可发现性缺陷</b>：不悬停就完全看不到还有别的 tab。
        窄屏做了常显兜底，宽屏没有。（这是产品取舍，但值得在 P2 复议。）
      </Flaw>
      <Flaw><b>过渡写 <code>duration-300</code></b>，没走 <code>--dur</code>（0.2s）—— 与其它组件的动效节奏不一致。</Flaw>
      <Flaw><b>圆角 <code>rounded-md</code>/<code>rounded-2xl</code> 绕过四档</b>。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── ConfirmDialog
function ConfirmSection() {
  const { confirm, dialog } = useConfirm();
  return (
    <Section id="confirm-dialog" name="ConfirmDialog / useConfirm" layer="组合件" file="ui/confirm-dialog.tsx" purpose="二次确认弹窗；requireText 用于主数据的强确认。">
      <Row label="普通确认">
        <Button size="sm" variant="outline" onClick={() => confirm({ title: "确认下发指令？", desc: "指令将立即发送到机柜 CAB-000123。" })}>打开</Button>
      </Row>
      <Row label="危险确认（danger）">
        <Button size="sm" variant="outline" onClick={() => confirm({ title: "归档机柜 CAB-000123", desc: "归档后不再出现在默认列表，历史数据保留。", danger: true, confirmText: "归档" })}>打开</Button>
      </Row>
      <Row label="强确认（requireText）" note="输入框内容必须与 CAB-000123 完全一致，确认按钮才解锁">
        <Button size="sm" variant="outline" onClick={() => confirm({ title: "归档机柜 CAB-000123", desc: "这是主数据，请输入编号确认。", danger: true, requireText: "CAB-000123" })}>打开</Button>
      </Row>
      <Row label="仅标题（无 desc）">
        <Button size="sm" variant="outline" onClick={() => confirm({ title: "确定要继续吗？" })}>打开</Button>
      </Row>
      {dialog}
      <Flaw><b>层级硬写 <code>z-40/z-50</code> + 阴影 <code>shadow-xl</code></b>（同 Drawer）。</Flaw>
      <Flaw><b>圆角 <code>rounded-[var(--radius)]</code>（14px）</b>，弹层大面应为 <code>rounded-sheet</code>（20px）。</Flaw>
      <Missing>确认按钮无 loading 态：异步操作时用户会连点，组件层挡不住（各页自己加 disabled）。</Missing>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── MultiSelect
const MS_OPTIONS = [
  { value: "AE", label: "阿联酋" }, { value: "SA", label: "沙特" },
  { value: "QA", label: "卡塔尔" }, { value: "KW", label: "科威特" },
];

function MultiSelectSection() {
  const [a, setA] = React.useState<string[]>([]);
  const [b, setB] = React.useState<string[]>(["AE", "SA"]);
  const [c, setC] = React.useState<string[]>(MS_OPTIONS.map((o) => o.value));
  return (
    <Section id="multi-select" name="MultiSelect" layer="组合件" file="ui/multi-select.tsx" purpose="轻量多选：触发区展示已选 chips，点开下拉勾选。">
      <Row label="空（占位）"><div className="w-64"><MultiSelect value={a} options={MS_OPTIONS} onChange={setA} placeholder="选择适用国家" /></div></Row>
      <Row label="部分选中"><div className="w-64"><MultiSelect value={b} options={MS_OPTIONS} onChange={setB} /></div></Row>
      <Row label="全选（chips 溢出）"><div className="w-64"><MultiSelect value={c} options={MS_OPTIONS} onChange={setC} /></div></Row>
      <Row label="disabled"><div className="w-64"><MultiSelect value={b} options={MS_OPTIONS} onChange={() => {}} disabled /></div></Row>
      <Row label="invalid（错误态）"><div className="w-64"><MultiSelect value={a} options={MS_OPTIONS} onChange={setA} invalid placeholder="必填" /></div></Row>
      <Row label="无可选项"><div className="w-64"><MultiSelect value={[]} options={[]} onChange={() => {}} placeholder="（无可选项）" /></div></Row>
      <Hint>
        本区块只<b>读</b>这个组件不改它 —— 另有一路在改 multi-select.tsx。此处呈现的是本次快照下的现状。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── FileField
/** 本页不真传文件：注入一个假上传（逐步进度；文件名含 fail 的必失败，用来看「重试」）。 */
const fakeUpload = async (file: File, category: FileCategory, onProgress?: (p: number) => void): Promise<FileRef> => {
  for (const p of [0.25, 0.5, 0.75]) { onProgress?.(p); await new Promise((r) => setTimeout(r, 250)); }
  if (/fail/i.test(file.name)) throw new Error("网络中断（演示）");
  onProgress?.(1);
  return {
    fileNo: `DEMO${Date.now() % 100000}`, category, status: "TEMP", originalName: file.name,
    contentType: file.type, sizeBytes: file.size, previewable: file.type.startsWith("image/"),
    imageWidth: null, imageHeight: null, uploadedAt: new Date().toISOString(),
  };
};

function FileFieldSection() {
  const [a, setA] = React.useState<string[]>([]);
  const [b, setB] = React.useState<string[]>([]);
  return (
    <Section id="file-field" name="FileField（FormDrawer type: file）" layer="组合件" file="ui/file-field.tsx" purpose="多文件上传：进度、失败重试、图片缩略图；按用途提示类型与大小。值 = 上传成功的 fileNo 数组。">
      <Row label="空（WO_PHOTO）" stack><div className="w-[420px]"><FileField value={a} onChange={setA} category="WO_PHOTO" upload={fakeUpload} /></div></Row>
      <Row label="选文件后：进度 → 缩略图" note="文件名含 fail 的会失败，出「重试」" stack>
        <div className="w-[420px]"><FileField value={b} onChange={setB} category="WO_PHOTO" max={3} upload={fakeUpload} /></div>
      </Row>
      <Row label="invalid（必填未传）" stack><div className="w-[420px]"><FileField value={[]} onChange={() => {}} category="CONTRACT_SCAN" invalid upload={fakeUpload} /></div></Row>
      <Row label="disabled" stack><div className="w-[420px]"><FileField value={[]} onChange={() => {}} category="WO_PHOTO" disabled upload={fakeUpload} /></div></Row>
      <Hint>
        选文件即上传（不等保存）：现场照片几 MB，保存时才传会让保存按钮转十几秒，且任一张失败整张表单失败。
        上传中 / 失败的不进值 —— 否则还没传完的 fileNo 会随表单提交。超类型 / 超大小在选择时就拦下，不白等一次 400。
      </Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── StatusBadge
function StatusBadgeSection() {
  return (
    <Section id="status-badge" name="StatusBadge / statusOptions" layer="组合件" file="ui/status-badge.tsx" purpose="「枚举 → 徽标」的统一渲染；映射表本身留在页面（业务语义）。">
      <Row label="按 StatusMap 渲染">
        {(Object.keys(DEMO_STATUS) as (keyof typeof DEMO_STATUS)[]).map((k) => (
          <StatusBadge key={k} map={DEMO_STATUS} value={k} />
        ))}
      </Row>
      <Row label="statusOptions 派生的下拉" note="顺序 = 映射表键序（改键序 = 改 UI）">
        <FilterSelect value="" onChange={() => {}} options={DEMO_STATUS} allLabel="全部状态" />
      </Row>
      <Flaw>
        <b>值不在映射表里就直接崩</b>：<code>map[value]</code> 取到 undefined 后读 <code>.tone</code> 抛错，
        整页白屏。后端加一个新枚举值（如新增 <code>MAINTAINING</code>）前端就挂 —— 应该退化成灰徽标显示原始值。
        （此处不敢演示，演示就把这页也一起搞崩了。）
      </Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── FilterSelect
function FilterSelectSection() {
  const [a, setA] = React.useState("");
  const [b, setB] = React.useState("MONTH");
  return (
    <Section id="filter-select" name="FilterSelect" layer="组合件" file="ui/filter-select.tsx" purpose="列表页筛选下拉；传 StatusMap 时选项自动派生。">
      <Row label="StatusMap 派生 + 全部项"><FilterSelect value={a} onChange={setA} options={DEMO_STATUS} allLabel="全部状态" /></Row>
      <Row label="数组 options + 无空值项（必选型）">
        <FilterSelect value={b} onChange={setB} options={[{ value: "MONTH", label: "按月结算" }, { value: "WEEK", label: "按周结算" }]} />
      </Row>
      <Row label="已选中某项"><FilterSelect value="FAULT" onChange={() => {}} options={DEMO_STATUS} allLabel="全部状态" /></Row>
      <Row label="空选项列表"><FilterSelect value="" onChange={() => {}} options={[]} allLabel="全部（无可选）" /></Row>
      <Flaw><b>继承了 Select 的全部问题</b>：无 disabled 视觉、圆角魔数、高度硬写。</Flaw>
      <Flaw><b>没有 disabled 入参</b>：无权限或加载中时页面只能把它藏起来，不能置灰。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Timeline
const TL_ITEMS: TimelineItem[] = [
  { key: "1", badge: { label: "人工退款", tone: "warning" }, meta: "OP-20260730-001 · 2026-07-30 14:02 · 张三",
    change: <span className="tabular-nums">AED 12.00 → AED 0.00</span>, text: "用户反馈机柜未弹出，核实后全额退款。" },
  { key: "2", badge: { label: "+10 分", tone: "success" }, meta: "CR-20260729-118 · 2026-07-29 09:31 · 系统", change: <span className="tabular-nums">620 → 630</span> },
  { key: "3", meta: "2026-07-28 18:00 · 李四", text: "仅有说明、无徽标无变化值的形态。" },
];

function TimelineSection() {
  return (
    <Section id="timeline" name="Timeline" layer="组合件" file="ui/timeline.tsx" purpose="审计时间线：时间 + 操作人 + 前后值 + 说明。">
      <Row label="有数据（三种形态）" stack><Timeline items={TL_ITEMS} empty="暂无留痕" /></Row>
      <Row label="loading" stack><Timeline items={[]} loading empty="暂无留痕" /></Row>
      <Row label="empty（推荐写法）" stack><Timeline items={[]} empty="该订单没有人工干预记录 —— 全部由系统自动结算。" /></Row>
      <Flaw>
        <b>竖线用 <code>border-l-2</code> 而不是逻辑属性</b>：RTL 下线还挂在左边，内容却从右边起排。
        打开顶部 RTL 开关自己看 —— 这是本页 RTL 开关抓到的第一个真缺陷。
      </Flaw>
      <Flaw><b>loadingText 默认中文硬编码</b>（&quot;加载中…&quot;），未走 i18n（同 Tree）。</Flaw>
      <Flaw><b>loading / empty 返回裸 <code>&lt;span&gt;</code></b>，不是 Skeleton / EmptyState（同 Tree）。</Flaw>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── SiteMap
const MAP_POINTS: MapPoint[] = [
  { id: "s1", name: "JBR 星巴克", lat: 25.078, lng: 55.133, count: 3, desc: "海滨商圈" },
  { id: "s2", name: "Dubai Mall B1", lat: 25.197, lng: 55.279, count: 8, desc: "购物中心" },
  { id: "s3", name: "机场 T3 到达层", lat: 25.253, lng: 55.365, count: 5, desc: "交通枢纽", alert: true },
];

function SiteMapSection() {
  return (
    <Section id="site-map" name="SiteMap" layer="组合件" file="ui/site-map.tsx" purpose="站点撒点地图；无 Maps key 或瓦片加载失败时降级为列表。">
      <Row label="有点位" note="本机通常无 NEXT_PUBLIC_GMAPS_KEY → 应看到降级列表" stack>
        <SiteMap points={MAP_POINTS} height={260} />
      </Row>
      <Row label="空点位" stack><SiteMap points={[]} height={180} /></Row>
      <Hint>降级列表是刻意设计（受限网络下不白屏），不是 bug。有 key 时这里会是真地图。</Hint>
    </Section>
  );
}

// ────────────────────────────────────────────────────────────── Toaster
function ToasterSection() {
  return (
    <Section id="toaster" name="Toaster" layer="组合件" file="ui/toaster.tsx" purpose="全局 toast（挂在 Providers 里），3.2s 自动消失。">
      <Row label="三种类型" note="右下角出现；RTL 下自动靠左（用了 insetInlineEnd）">
        <Button size="sm" variant="outline" onClick={() => notify.success("指令已下发")}>success</Button>
        <Button size="sm" variant="outline" onClick={() => notify.error("下发失败：机柜离线")}>error</Button>
        <Button size="sm" variant="outline" onClick={() => notify.info("演示信息")}>info</Button>
      </Row>
      <Row label="堆叠">
        <Button size="sm" variant="outline" onClick={() => { notify.success("第 1 条"); notify.error("第 2 条"); notify.info("第 3 条"); }}>连发 3 条</Button>
      </Row>
      <Row label="超长文案"><Button size="sm" variant="outline" onClick={() => notify.error("下发失败：机柜 CAB-000123 当前离线，最近一次心跳时间 2026-07-30 12:00，请先检查网络后重试")}>长文案</Button></Row>
      <Flaw>
        <b>文字直接用语义色原值</b>（<code>text-[var(--success)]</code> / <code>text-[var(--destructive)]</code>）压在 16% 混色底上，
        没走 <code>--*-ink</code> —— 与 Badge 修过的坑同源。
      </Flaw>
      <Flaw><b>层级硬写 <code>z-[100]</code></b>，没走 <code>z-[var(--z-toast)]</code>（=70）。</Flaw>
      <Flaw><b>关闭按钮 aria-label 中文硬编码</b>（&quot;关闭&quot;），未走 i18n。</Flaw>
      <Flaw><b>圆角 <code>rounded-xl</code> 绕过四档</b>；<code>ml-1</code> 是物理方向，RTL 下间距跑到错误一侧。</Flaw>
      <Missing>无 <code>role=&quot;status&quot;</code> / aria-live：读屏用户完全感知不到 toast。</Missing>
    </Section>
  );
}
